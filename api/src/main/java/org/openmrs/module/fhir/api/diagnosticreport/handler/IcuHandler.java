package org.openmrs.module.fhir.api.diagnosticreport.handler;
import ca.uhn.fhir.rest.server.exceptions.MethodNotAllowedException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hl7.fhir.dstu3.model.Attachment;
import org.hl7.fhir.dstu3.model.Coding;
import org.hl7.fhir.dstu3.model.DiagnosticReport;
import org.hl7.fhir.dstu3.model.IdType;
import org.hl7.fhir.dstu3.model.Observation;
import org.hl7.fhir.dstu3.model.Reference;
import org.openmrs.Encounter;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.Visit;
import org.openmrs.api.APIException;
import org.openmrs.api.EncounterService;
import org.openmrs.api.context.Context;
import org.openmrs.module.fhir.api.ObsService;
import org.openmrs.module.fhir.api.diagnosticreport.DiagnosticReportHandler;
import org.openmrs.module.fhir.api.util.FHIRConstants;
import org.openmrs.module.fhir.api.util.FHIRObsUtil;
import org.openmrs.module.fhir.api.util.FHIRUtils;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class IcuHandler extends AbstractHandler implements DiagnosticReportHandler {

	private static final String ServiceCategory = "ICU";

	private static final String RESOURCE_TYPE = "DiagnosticReport";

	protected final Log log = LogFactory.getLog(this.getClass());

	public IcuHandler() {
		super();
	}

	public String getServiceCategory() {
		return ServiceCategory;
	}

	@Override
	public DiagnosticReport getFHIRDiagnosticReportById(String id) {
		return getFHIRDiagnosticReport(Context.getEncounterService().getEncounterByUuid(id));
	}

	@Override
	public List<DiagnosticReport> getFHIRDiagnosticReportBySubjectName(String name) {
		return null;
	}

	private DiagnosticReport getFHIRDiagnosticReport(Encounter omrsDiagnosticReport) {
		log.debug("ICU Handler : GetFHIRDiagnosticReport");
		DiagnosticReport diagnosticReport = new DiagnosticReport();

		// Separate Obs into different field based on Concept Id
		Map<String, Set<Obs>> obsSetsMap = separateObs(omrsDiagnosticReport.getObsAtTopLevel(false), false);

		// Set ID
		diagnosticReport.setId(new IdType(RESOURCE_TYPE, omrsDiagnosticReport.getUuid()));

		// Get Obs and set as `Name`
		// Get Obs and set as `Status`

		return generateDiagnosticReport(diagnosticReport, omrsDiagnosticReport, obsSetsMap);
	}

	@Override
	public DiagnosticReport saveFHIRDiagnosticReport(DiagnosticReport diagnosticReport) {
		log.debug("ICU Handler : SaveFHIRDiagnosticReport");
		EncounterService encounterService = Context.getEncounterService();
		Encounter omrsDiagnosticReport = new Encounter();
		for (Reference referenceDt : diagnosticReport.getResult()) {
			System.out.println("referenceDt");
			System.out.println(referenceDt);
		}
		// Set `Name` as a Obs
		// Set `Status` as a Obs
		Visit visit = null;
		// @require: Set `Issued` date as EncounterDateTime
		omrsDiagnosticReport.setEncounterDatetime(diagnosticReport.getIssued());
		String encRef = diagnosticReport.getContext().getReference();
		if(encRef != null) {
			String enc_uuid = FHIRUtils.extractUuid(encRef);
			visit = Context.getVisitService().getVisitByUuid(enc_uuid);
		}

		Patient omrsPatient = getPatientFromReport(omrsDiagnosticReport, diagnosticReport.getSubject());

		List<Coding> codingList = getCodingList(diagnosticReport, omrsDiagnosticReport);

		String encounterType = FHIRConstants.DEFAULT; // If serviceCategory is not present in the DiagnosticReport, then use "DEFAULT"
		if (!codingList.isEmpty()) {
			encounterType = codingList.get(0).getCode();
		}
		omrsDiagnosticReport.setEncounterType(FHIRUtils.getEncounterType(encounterType));
		omrsDiagnosticReport.setVisit(visit);

		// Set `Diagnosis[x]->DateTime` as valueDateTime in an Obs
		// Set `Diagnosis[x]->Period` as valueDateTime in an Obs

		/**
		 * Create resource in OpenMRS Database RATIONALE: Due to encounter.setObs(obsList) method is
		 * not working properly and need to set encounter for the Obs before create them to link
		 * with the Encounter. In order to set the Encounter, it has to be save before set.
		 */
		Encounter omrsEncounter = encounterService.saveEncounter(omrsDiagnosticReport);
		
		
		addObservationsToTheGroup(diagnosticReport, omrsPatient, omrsEncounter);

		// Set Binary Obs Handler which used to store `PresentedForm`
		for (Attachment attachment : diagnosticReport.getPresentedForm()) {
			int conceptId = FHIRUtils.getDiagnosticReportPresentedFormConcept().getConceptId();
			setAttachmentCreation(diagnosticReport, attachment);
			saveComplexData(omrsDiagnosticReport, conceptId, omrsPatient, attachment);
		}
		/**
		 * TODO: Not working properly. Need to test it. omrsDiagnosticReport.setObs(obsList);
		 */

		diagnosticReport.setId(new IdType(RESOURCE_TYPE, omrsEncounter.getUuid()));
		return diagnosticReport;
	}

	@Override
	public DiagnosticReport updateFHIRDiagnosticReport(DiagnosticReport diagnosticReport, String theId) {
		log.debug("ICU Handler : UpdateFHIRDiagnosticReport with ID" + theId);

		org.openmrs.api.ObsService obsService = Context.getObsService();
		EncounterService encounterService = Context.getEncounterService();
		Encounter omrsDiagnosticReport = encounterService.getEncounterByUuid(theId);

		// Separate Obs into different field such as `Name`, `Status`, `Result` and `PresentedForm` based on Concept Id
		Map<String, Set<Obs>> obsSetsMap = separateObs(omrsDiagnosticReport.getObsAtTopLevel(false), false);

		// Set `Name` as a Obs
		// Set `Status` as a Obs

		// If available set `Issued` date as EncounterDateTime
		if (diagnosticReport.getIssued() != null) {
			omrsDiagnosticReport.setEncounterDatetime(diagnosticReport.getIssued());
		}

		Patient omrsPatient = getPatientFromReport(omrsDiagnosticReport, diagnosticReport.getSubject());

		List<Coding> codingList = getCodingList(diagnosticReport, omrsDiagnosticReport);

		if (!codingList.isEmpty()) {
			String encounterType = codingList.get(0).getCode();
			omrsDiagnosticReport.setEncounterType(FHIRUtils.getEncounterType(encounterType));
		}

		// Set `Diagnosis[x]->DateTime` as valueDateTime in an Obs
		// Set `Diagnosis[x]->Period` as valueDateTime in an Obs

		// Update resource in OpenMRS Database
		Encounter omrsEncounter = encounterService.saveEncounter(omrsDiagnosticReport);

		// Set parsed obsSet (`Result` as Set of Obs)
		// Void existing `Result` values. Since this field is saved as an Obs Group, all group members will be voided.
		java.util.Date date = new java.util.Date();
		for (Obs resultObs : obsSetsMap.get(FHIRConstants.DIAGNOSTIC_REPORT_RESULT)) {
			obsService.voidObs(resultObs, "Due to update DiagnosticReport on " + new Timestamp(date.getTime()));
		}
		// Store new `Result` values

		addObservationsToTheGroup(diagnosticReport, omrsPatient, omrsEncounter);

		// Update Binary Obs Handler which used to store `PresentedForm`
		// Void existing `PresentedForm` values
		for (Obs attachmentObs : obsSetsMap.get(FHIRConstants.DIAGNOSTIC_REPORT_PRESENTED_FORM)) {
			voidAttachment(attachmentObs);
		}
		obsSetsMap.remove(FHIRConstants.DIAGNOSTIC_REPORT_PRESENTED_FORM);
		// Store new `PresentedForm` values
		for (Attachment attachment : diagnosticReport.getPresentedForm()) {
			setAttachmentCreation(diagnosticReport, attachment);
			int conceptId = FHIRUtils.getDiagnosticReportPresentedFormConcept().getConceptId();
			saveComplexData(omrsDiagnosticReport, conceptId, omrsPatient, attachment);
		}

		diagnosticReport.setId(new IdType(RESOURCE_TYPE, omrsEncounter.getUuid()));
		return diagnosticReport;
	}

	private void setAttachmentCreation(DiagnosticReport diagnosticReport, Attachment attachment) {
		if (attachment.getCreation() == null) {
			if (diagnosticReport.getIssued() != null) {
				attachment.setCreation(diagnosticReport.getIssued());
			}
		}
	}

	private void voidAttachment(Obs attachmentObs) {
		org.openmrs.api.ObsService obsService = Context.getObsService();
		int obsId = attachmentObs.getObsId();
		Obs complexObs = obsService.getComplexObs(obsId, "RAW_VIEW");
		java.util.Date date = new java.util.Date();
		obsService.voidObs(complexObs, "Due to update DiagnosticReport on " + new Timestamp(date.getTime()));
	}
	

	private void oaddObservationsToTheGroup(DiagnosticReport diagnosticReport, Patient omrsPatient, Encounter omrsEncounter) {
		// Set parsed obsSet (`Result` as Set of Obs)
		Set<Obs> resultObsGroupMembersSet = new HashSet<>();
		// Iterate through 'result' Observations and adding to the OpenMRS Obs group
		for (Reference referenceDt : diagnosticReport.getResult()) {
			List<String> errors = new ArrayList<>();
			Observation observation;

			if (referenceDt.getReference() != null) {
				observation = (Observation) referenceDt.getResource();
			} else {
				// Get Id of the Observation
				String observationID = referenceDt.getId();
				// Assume that the given Observation is stored in the OpenMRS database
				observation = Context.getService(ObsService.class).getObs(observationID);
			}

			Obs obs = FHIRObsUtil.generateOpenMRSObs(prepareForGenerateOpenMRSObs(observation, diagnosticReport), errors);
			/**
			 * TODO: Unable to check for errors because it's sending errors also for not mandatory
			 * fields if(errors.isEmpty()) {}
			 */
			obs = Context.getObsService().saveObs(obs, "test1");
			resultObsGroupMembersSet.add(obs);
		}

		if (!resultObsGroupMembersSet.isEmpty()) {
			Obs resultObsGroup = getObsGroup(diagnosticReport, omrsPatient, omrsEncounter, resultObsGroupMembersSet,
					FHIRUtils.getDiagnosticReportResultConcept());

		}
	}

	private void addObservationsToTheGroup(DiagnosticReport diagnosticReport, Patient omrsPatient, Encounter omrsEncounter) {
		// Set parsed obsSet (`Result` as Set of Obs)
		Set<Obs> resultObsGroupMembersSet = new HashSet<>();
		// @required: Set `Subject` as Encounter Patient
		Reference subjectReference = diagnosticReport.getSubject();
		// Iterate through 'result' Observations and adding to the OpenMRS Obs group
		for (Reference referenceDt : diagnosticReport.getResult()) {
			List<String> errors = new ArrayList<>();
			Observation observation = null;
			if (!referenceDt.getReference().isEmpty()) {
				observation = (Observation) referenceDt.getResource();
				System.out.println("observationID1");
				System.out.println(observation.getId());
			} else {
				// Get Id of the Observation
				String observationID = referenceDt.getId();
				if (StringUtils.isEmpty(observationID)) {
					// Assume that the given Observation is stored in the OpenMRS database
					observation = Context.getService(org.openmrs.module.fhir.api.ObsService.class).getObs(observationID);
					System.out.println("observationID2");
					System.out.println(observationID);
				} else {
					String observationReference = referenceDt.getReference();
					System.out.println("observationID3");
					System.out.println(observationID);
					if (!StringUtils.isEmpty(observationReference) && "/".contains(observationReference)) {
						observationID = observationReference.split("/")[1];
						observation = Context.getService(org.openmrs.module.fhir.api.ObsService.class).getObs(observationID);
					}
				}
			}
			observation.setSubject(subjectReference);
			Obs obs = FHIRObsUtil.generateOpenMRSObs(prepareForGenerateOpenMRSObs(observation, diagnosticReport), errors);
			obs.setEncounter(omrsEncounter);
			obs = Context.getObsService().saveObs(obs, "test0");
			
			//int numOrg = observation.getRelated().size();
            //Set<Obs> orgObs = new HashSet<Obs>();
            //for (int i = 0; i < numOrg; i++) {
             //    String orgRef = observation.getRelated().get(i).getTarget().getReference();
             //    System.out.println(orgRef);
             //    Observation org_observation = null;
             //    org_observation = (Observation) observation.getRelated().get(i).getTarget().getResource();
             //    org_observation.setSubject(diagnosticReport.getSubject());
             //    int numAgent = org_observation.getRelated().size();
             //    Set<Obs> agentObs = new HashSet<Obs>();
             //    Obs oObs = FHIRObsUtil.generateOpenMRSObs(org_observation, errors);
             //    //oObs.setInterpretation(convertInterpretation(org_observation));
             //    oObs.setObsDatetime(diagnosticReport.getIssued());
             //    oObs.setObsGroup(obs);
             //    Context.getObsService().saveObs(oObs,null);
        
             //}
			
			
			/**
			 * TODO: Unable to check for errors because it's sending errors also for not mandatory
			 * fields if(errors.isEmpty()) {}
			 */
			//obs = Context.getObsService().saveObs(obs, "test1");
			resultObsGroupMembersSet.add(obs);
		}

	//	if (!resultObsGroupMembersSet.isEmpty()) {
	//		Obs resultObsGroup = getObsGroup(diagnosticReport, omrsPatient, omrsEncounter, resultObsGroupMembersSet,
	//				FHIRUtils.getDiagnosticReportResultConcept());

//			Context.getObsService().saveObs(resultObsGroup, "test2");
	//	}
	}

	private Observation prepareForGenerateOpenMRSObs(Observation observation, DiagnosticReport diagnosticReport) {
		observation.setSubject(diagnosticReport.getSubject());
		observation.setIssued(diagnosticReport.getIssued());
		diagnosticReport.getContext();
		return observation;
	}

	@Override
	public void retireFHIRDiagnosticReport(String id) {
		log.debug("ICU Handler : RetireFHIRDiagnosticReport with ID " + id);
		EncounterService encounterService = Context.getEncounterService();
		// Delete Binary Obs Handler which used to store `PresentedForm`

		// Delete Encounter OpenMRS Object
		Encounter omrsDiagnosticReport = encounterService.getEncounterByUuid(id);

		if (omrsDiagnosticReport == null) {
			throw new ResourceNotFoundException(String.format("Diagnostic Report with id '%s' not found.", id));
		}
		if (omrsDiagnosticReport.isVoided()) {
			return;
		}
		try {
			encounterService.voidEncounter(omrsDiagnosticReport, "Voided by FHIR Request.");
		}
		catch (APIException exAPI) {
			throw new MethodNotAllowedException(String.format("OpenMRS has failed to retire Encounter '%s' due to : %s", id,
					exAPI.getMessage()));
		}
	}
}
