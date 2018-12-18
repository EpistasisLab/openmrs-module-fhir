package org.openmrs.module.fhir.api.diagnosticreport.handler;
import ca.uhn.fhir.rest.gclient.ICriterion;
import ca.uhn.fhir.rest.gclient.ReferenceClientParam;
import ca.uhn.fhir.rest.gclient.TokenClientParam;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hl7.fhir.dstu3.model.Attachment;
import org.hl7.fhir.dstu3.model.Bundle;
import org.hl7.fhir.dstu3.model.CodeableConcept;
import org.hl7.fhir.dstu3.model.Coding;
import org.hl7.fhir.dstu3.model.DiagnosticReport;
import org.hl7.fhir.dstu3.model.IdType;
import org.hl7.fhir.dstu3.model.ImagingStudy;
import org.hl7.fhir.dstu3.model.Observation;
import org.hl7.fhir.dstu3.model.Observation.ObservationRelatedComponent;
import org.hl7.fhir.dstu3.model.Patient;
import org.hl7.fhir.dstu3.model.Reference;
import org.hl7.fhir.dstu3.model.Resource;
import org.openmrs.Concept;
import org.openmrs.Encounter;
import org.openmrs.EncounterRole;
import org.openmrs.Obs;
import org.openmrs.Provider;
import org.openmrs.Visit;
import org.openmrs.Obs.Interpretation;
import org.openmrs.api.EncounterService;
import org.openmrs.api.ObsService;
import org.openmrs.api.context.Context;
import org.openmrs.module.fhir.api.diagnosticreport.DiagnosticReportHandler;
import org.openmrs.module.fhir.api.util.ErrorUtil;
import org.openmrs.module.fhir.api.util.FHIRConstants;
import org.openmrs.module.fhir.api.util.FHIRImagingStudyUtil;
import org.openmrs.module.fhir.api.util.FHIRObsUtil;
import org.openmrs.module.fhir.api.util.FHIRPatientUtil;
import org.openmrs.module.fhir.api.util.FHIRRESTfulGenericClient;
import org.openmrs.module.fhir.api.util.FHIRUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;



public class MicrobiologyHandler extends AbstractHandler implements DiagnosticReportHandler {

	private static final String ServiceCategory = "MB";

	protected final Log log = LogFactory.getLog(this.getClass());

	public MicrobiologyHandler() {
		super();
	}

	@Override
	public String getServiceCategory() {
		return ServiceCategory;
	}

	@Override
	public DiagnosticReport getFHIRDiagnosticReportById(String id) {
		return getFHIRDiagnosticReport(Context.getEncounterService().getEncounterByUuid(id));
	}

	/**
	 * Getting the data from a third party server, storing metadata in OpenMRS and passing on the bundle to the requester.
	 *
	 * @param name Given Name of the Subject/Patient
	 * @return A bundle of DiagnosticReport which is matching with Given Name
	 */
	@Override
	public List<DiagnosticReport> getFHIRDiagnosticReportBySubjectName(String name) {
		if (log.isDebugEnabled()) {
			log.debug("GetFHIRDiagnosticReportBySubjectName : " + name);
		}
		List<DiagnosticReport> diagnosticReports = new ArrayList<DiagnosticReport>();

		String serverBase = FHIRUtils.getDiagnosticReportRadiologyBaseServerURL();
		ICriterion<ReferenceClientParam> diagnosticReportBySubject = DiagnosticReport.SUBJECT.hasChainedProperty(
				Patient.GIVEN.matches().value(name));
		ICriterion<TokenClientParam> diagnosticReportByService = DiagnosticReport.CATEGORY.exactly().code("MB");
		Bundle bundle = FHIRRESTfulGenericClient.searchWhereReferenceAndToken(serverBase, DiagnosticReport.class,
				diagnosticReportBySubject,
				diagnosticReportByService);
		if (log.isDebugEnabled()) {
			log.debug("Bundle size : " + bundle.getTotal());
		}

		for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
			Resource resource = entry.getResource();
			if (log.isDebugEnabled()) {
				log.debug("Resource Type : " + resource.getResourceType().name());
			}
			if (FHIRConstants.DIAGNOSTIC_REPORT.equals(resource.getResourceType().name())) {
				DiagnosticReport diagnosticReport = (DiagnosticReport) resource;
				diagnosticReport = this.saveFHIRDiagnosticReport(diagnosticReport);
				diagnosticReport = this.getFHIRDiagnosticReportById(diagnosticReport.getId());

				diagnosticReports.add(diagnosticReport);
			}
		}

		return diagnosticReports;
	}

	private DiagnosticReport getFHIRDiagnosticReport(Encounter omrsDiagnosticReport) {
		DiagnosticReport diagnosticReport = new DiagnosticReport();

		// Separate Obs into different field based on Concept Id
		Map<String, Set<Obs>> obsSetsMap = separateObs(omrsDiagnosticReport.getObsAtTopLevel(false), true);
		//System.out.println("count");
		//System.out.println(obsSetsMap.size());
		// Set ID
		diagnosticReport.setId(new IdType(FHIRConstants.DIAGNOSTIC_REPORT, omrsDiagnosticReport.getUuid()));

		// Get Obs and set as `Name`
		// Get Obs and set as `Status`

		
		// @required: Get EncounterDateTime and set as `Issued` date
		diagnosticReport.setIssued(omrsDiagnosticReport.getEncounterDatetime());

		// @required: Get Encounter Patient and set as `Subject`
		org.openmrs.Patient omrsPatient = omrsDiagnosticReport.getPatient();
		diagnosticReport.getSubject().setResource(FHIRPatientUtil.generatePatient(omrsPatient));

		// Get Encounter Provider and set as `Performer`
		EncounterRole omrsEncounterRole = FHIRUtils.getEncounterRole();
		Set<Provider> omrsProviderList = omrsDiagnosticReport.getProvidersByRole(omrsEncounterRole);
		// If at least one provider is set (1..1 mapping in FHIR Diagnostic Report)
		if (!omrsProviderList.isEmpty()) {
			//Role name to a getCodingList display. Is that correct?
			for (Provider practitioner : omrsProviderList) {
				CodeableConcept roleConcept = new CodeableConcept();
				Coding role = new Coding();
				role.setDisplay(omrsEncounterRole.getName());
				roleConcept.addCoding(role);
				Reference practitionerReference = FHIRUtils.buildPractitionerReference(practitioner);
				DiagnosticReport.DiagnosticReportPerformerComponent performer = diagnosticReport.addPerformer();
				performer.setRole(roleConcept);
				performer.setActor(practitionerReference);
			}
		}

		// Get EncounterType and Set `ServiceCategory`
		String serviceCategory = omrsDiagnosticReport.getEncounterType().getName();
		List<Coding> serviceCategoryList = new ArrayList<>();
		serviceCategoryList.add(new Coding(FHIRConstants.CODING_0074, serviceCategory, serviceCategory));
		diagnosticReport.getCategory().setCoding(serviceCategoryList);

		// Get valueDateTime in Obs and Set `Diagnosis[x]->DateTime`
		// Get valueDateTime in Obs and Set `Diagnosis[x]->Period`

		// ObsSet set as `Result`
		List<Reference> resultReferenceDtList = new ArrayList<>();
		List<Resource> containedResourceList = new ArrayList<>();
		for (Obs resultObs : obsSetsMap.get(FHIRConstants.DIAGNOSTIC_REPORT_RESULT)) {
			for (Obs obs : resultObs.getGroupMembers())
			{
				Observation observation = FHIRObsUtil.generateObs(obs);
				observation.setInterpretation(invertInterpretation(obs));
				observation.setId(new IdType());
				
				List<Observation.ObservationRelatedComponent> relObs = new ArrayList<>();
				for (Obs oobs : obs.getGroupMembers()) {
					List<Observation.ObservationRelatedComponent> arelObs = new ArrayList<>();
					for (Obs bobs : oobs.getGroupMembers()) {
						Observation aobservation = FHIRObsUtil.generateObs(bobs);
						aobservation.setId(new IdType());
						aobservation.setInterpretation(invertInterpretation(bobs));
						Observation.ObservationRelatedComponent aorc = new ObservationRelatedComponent();
						aorc.setTarget(new Reference(aobservation));
						arelObs.add(aorc);
					}
					Observation oobservation = FHIRObsUtil.generateObs(oobs);
					oobservation.setId(new IdType());
					oobservation.setRelated(arelObs);
					//nullify member value strings
					oobservation.setValue(null);
					Observation.ObservationRelatedComponent oorc = new ObservationRelatedComponent();
					oorc.setTarget(new Reference(oobservation));
					relObs.add(oorc);
				}
				observation.setRelated(relObs);			
				resultReferenceDtList.add(new Reference(observation));
			}
			
		}
		if (!resultReferenceDtList.isEmpty()) {
			diagnosticReport.setResult(resultReferenceDtList);
		}

		return diagnosticReport;
	}


	@Override
	public DiagnosticReport saveFHIRDiagnosticReport(DiagnosticReport diagnosticReport) {
		if (log.isDebugEnabled()) {
			log.debug("Saving FHIR DiagnosticReport " + diagnosticReport.getId());
		}
		EncounterService encounterService = Context.getEncounterService();
		ObsService obsService = Context.getObsService();
		Encounter omrsDiagnosticReport = new Encounter();
		Visit visit = null;

		// @require: Set `Issued` date as EncounterDateTime
		omrsDiagnosticReport.setEncounterDatetime(diagnosticReport.getIssued());
		String encRef = diagnosticReport.getContext().getReference();
		if(encRef != null) {
			String enc_uuid = FHIRUtils.extractUuid(encRef);
			visit = Context.getVisitService().getVisitByUuid(enc_uuid);
		}

		// @required: Set `Subject` as Encounter Patient
		Reference subjectReference = diagnosticReport.getSubject();
		
		org.openmrs.Patient omrsPatient = getPatientFromReport(omrsDiagnosticReport, subjectReference);

		List<Coding> codingList = getCodingList(diagnosticReport, omrsDiagnosticReport);
		String encounterType = FHIRConstants.DEFAULT; // If serviceCategory is not present in the DiagnosticReport, then use "DEFAULT"
		if (!codingList.isEmpty()) {
			//TODO: Need to fix. multiple codes
			encounterType = codingList.get(0).getCode();
		}
		omrsDiagnosticReport.setEncounterType(FHIRUtils.getEncounterType(encounterType));
		omrsDiagnosticReport.setVisit(visit);

		/**
		 * Create resource in OpenMRS Database RATIONALE: Due to encounter.setObs(obsList) method is
		 * not working properly and need to set encounter for the Obs before create them to link
		 * with the Encounter. In order to set the Encounter, it has to be save before set.
		 */
		Encounter omrsEncounter = encounterService.saveEncounter(omrsDiagnosticReport);

		/****************************** Set `Result` field *************************************************/
		// Set parsed obsSet (`Result` as Set of Obs)
		Set<Obs> resultObsGroupMembersSet = new HashSet<>();
		Obs resultObsGroup = new Obs(Context.getPersonService().getPersonByUuid(omrsPatient.getUuid()),
				FHIRUtils.getDiagnosticReportResultConcept(),
				diagnosticReport.getIssued(), null);
		resultObsGroup.setEncounter(omrsEncounter);
		obsService.saveObs(resultObsGroup, null);
		// Iterate through 'result' Observations and adding to the OpenMRS Obs group
		for (Reference referenceDt : diagnosticReport.getResult()) {
			List<String> errors = new ArrayList<>();
			Observation observation = null;
			if (!referenceDt.getReference().isEmpty()) {
				observation = (Observation) referenceDt.getResource();
				observation.setSubject(subjectReference);
			} else {
				// Get Id of the Observation
				String observationID = referenceDt.getId();
				if (StringUtils.isEmpty(observationID)) {
					// Assume that the given Observation is stored in the OpenMRS database
					observation = Context.getService(org.openmrs.module.fhir.api.ObsService.class).getObs(observationID);
				} else {
					String observationReference = referenceDt.getReference();
					System.out.println("observationID");
					System.out.println(observationID);
					if (!StringUtils.isEmpty(observationReference) && "/".contains(observationReference)) {
						observationID = observationReference.split("/")[1];
						observation = Context.getService(org.openmrs.module.fhir.api.ObsService.class).getObs(observationID);
					}
				}
			}
			observation.setSubject(diagnosticReport.getSubject());
			Obs obs = FHIRObsUtil.generateOpenMRSObs(observation, errors);
			obs.setObsDatetime(diagnosticReport.getIssued());
			obs.setObsGroup(resultObsGroup);
			obs.setInterpretation(convertInterpretation(observation));
			obs = obsService.saveObs(obs, null);
			int numOrg = observation.getRelated().size();
            Set<Obs> orgObs = new HashSet<Obs>();
            for (int i = 0; i < numOrg; i++) {
                 String orgRef = observation.getRelated().get(i).getTarget().getReference();
                 System.out.println(orgRef);
                 Observation org_observation = null;
                 org_observation = (Observation) observation.getRelated().get(i).getTarget().getResource();
                 org_observation.setSubject(diagnosticReport.getSubject());
                 int numAgent = org_observation.getRelated().size();
                 Set<Obs> agentObs = new HashSet<Obs>();
                 Obs oObs = FHIRObsUtil.generateOpenMRSObs(org_observation, errors);
                 //oObs.setInterpretation(convertInterpretation(org_observation));
                 oObs.setObsDatetime(diagnosticReport.getIssued());
                 oObs.setObsGroup(obs);
                 Context.getObsService().saveObs(oObs,null);
                 for (int j = 0; j < numAgent; j++) {
                	 Observation agent_observation = null;
                	 agent_observation = (Observation) org_observation.getRelated().get(j).getTarget().getResource();
                	 agent_observation.setSubject(diagnosticReport.getSubject());
                 	 Obs aObs = FHIRObsUtil.generateOpenMRSObs(agent_observation, errors);
                 	 aObs.setObsDatetime(diagnosticReport.getIssued());
                 	 aObs.setInterpretation(convertInterpretation(agent_observation));
                 	 aObs.setObsGroup(oObs);
                 	 Context.getObsService().saveObs(aObs, null);
                 }
                 oObs.setObsGroup(obs);
             }
		}

		/****************************** Set `ImagingStudy` as a set of Obs *********************************/
		Set<Obs> imagingStudyObsGroupMembersSet = new HashSet<>();
		for (Reference referenceDt : diagnosticReport.getImagingStudy()) {

			Obs obs;
			if (!referenceDt.getReference().isEmpty()) {
				List<String> errors = new ArrayList<>();
				ImagingStudy imagingStudy = (ImagingStudy) referenceDt.getResource();
				obs = FHIRImagingStudyUtil.generateOpenMRSImagingStudy(imagingStudy, errors);
				if (!errors.isEmpty()) {
					String errorMessage = ErrorUtil.generateErrorMessage(errors, FHIRConstants.REQUEST_ISSUE_LIST);
					throw new UnprocessableEntityException(errorMessage);
				}
			} else {
				// Get Id of the ImagingStudy
				String imagingStudyId = referenceDt.getId();
				// Get `ImagingStudy` Obs from external server
				obs = this.getOpenMRSImagingStudyObs(imagingStudyId);
			}
			obs = obsService.saveObs(obs, null);
			imagingStudyObsGroupMembersSet.add(obs);
		}
		if (!imagingStudyObsGroupMembersSet.isEmpty()) {
			Obs imagingStudyObsGroup = getObsGroup(diagnosticReport, omrsPatient, omrsEncounter, resultObsGroupMembersSet,
					FHIRUtils.getDiagnosticReportImagingStudyConcept());
			obsService.saveObs(imagingStudyObsGroup, null);
		} //-- Set `ImagingStudy` as a set of Obs

		// Set Binary Obs Handler which used to store `PresentedForm`
		for (Attachment attachment : diagnosticReport.getPresentedForm()) {
			int conceptId = FHIRUtils.getDiagnosticReportPresentedFormConcept().getConceptId();
			if (attachment.getCreation() == null) {
				if (diagnosticReport.getIssued() != null) {
					attachment.setCreation(diagnosticReport.getIssued());
				}
			}
			saveComplexData(omrsDiagnosticReport, conceptId, omrsPatient, attachment);
		}
		// TODO: Not working properly. Need to test it. omrsDiagnosticReport.setObs(obsList);

		diagnosticReport.setId(new IdType(FHIRConstants.DIAGNOSTIC_REPORT, omrsEncounter.getUuid()));
		return diagnosticReport;
	}

	/**
	 * Simple Hack for get generateOpenMRSObs get worked
	 *
	 * @param observation      FHIR Observation
	 * @param diagnosticReport FHIR DiagnosticReport
	 * @return FHIR Observation
	 */
	private Observation prepareForGenerateOpenMRSObs(Observation observation, DiagnosticReport diagnosticReport) {
		observation.setSubject(diagnosticReport.getSubject());
		observation.setIssued(diagnosticReport.getIssued());
		return observation;
	}

	/**
	 * Check whether there is a Provider in the Database for given uuid. If it's existing, then retrieve it, otherwise
	 * retrieve from third party server, generate Obs group which represent given ImangingStudy and return it back.
	 * NOTE: This method is not saving the Obs
	 *
	 * @param imagingStudyId FHIR ImagingStudy resource
	 * @return A OpenMRS Obs group
	 */
	private Obs getOpenMRSImagingStudyObs(String imagingStudyId) {
		// Check whether ImagingStudy Obs is already exist
		Obs imagingStudyObs = Context.getObsService().getObsByUuid(imagingStudyId);
		if (imagingStudyObs == null) {
			// ImagingStudy isn't in the database, then retrieve it
			String serverBase = FHIRUtils.getDiagnosticReportRadiologyBaseServerURL();
			ImagingStudy imagingStudy = FHIRRESTfulGenericClient.readImagingStudyById(serverBase, imagingStudyId);
			// Generate OpenMRS Obs from ImagingStudy Resource
			List<String> errors = new ArrayList<>();
			imagingStudyObs = FHIRImagingStudyUtil.generateOpenMRSImagingStudy(imagingStudy, errors);
			if (errors.isEmpty()) {
				return imagingStudyObs;
			} else {
				String errorMessage = ErrorUtil.generateErrorMessage(errors);
				if (log.isErrorEnabled()) {
					log.error("ImagingStudy create error : " + errorMessage);
				}
				throw new UnprocessableEntityException(errorMessage);
			}
		} else {
			return imagingStudyObs;
		}
	}
	
	private CodeableConcept invertInterpretation(Obs observation) {
		Interpretation interpretation = observation.getInterpretation();;
	    Coding code = new Coding();
	    CodeableConcept concept = new CodeableConcept();
	    String valueConceptCode = null;
	    String valueSystem = null;
	    if(interpretation ==  Interpretation.RESISTANT) {
	    	valueConceptCode = "R";
	    	valueSystem = "http://hl7.org/fhir/v2/0078";
	    }
	    if(interpretation ==  Interpretation.INTERMEDIATE) {
	    	valueConceptCode = "I";
	    	valueSystem = "http://hl7.org/fhir/v2/0078";
	    }
	    if(interpretation ==  Interpretation.SUSCEPTIBLE) {
	    	valueConceptCode = "S";
	    	valueSystem = "http://hl7.org/fhir/v2/0078";
	    }
	    if(interpretation ==  Interpretation.POSITIVE) {
	    	valueConceptCode = "POS";
	    	valueSystem = "http://hl7.org/fhir/v2/0078";
	    }
	    if(interpretation ==  Interpretation.NEGATIVE) {
	    	valueConceptCode = "NEG";
	    	valueSystem = "http://hl7.org/fhir/v2/0078";
	    }
	    code.setSystem(valueSystem);
	    code.setCode(valueConceptCode);
	    concept.addCoding(code);
		return  concept;
		}
	
	private Interpretation convertInterpretation(Observation observation) {
	CodeableConcept obs_interpretation = observation.getInterpretation();
    List<Coding> codingDts = obs_interpretation.getCoding();
    String valueConceptCode = null;
    String valueSystem = null;
    Interpretation interpretation = null;
    if (codingDts.size() > 0) {
    	Coding codingDt = codingDts.get(0);
    	valueConceptCode = codingDt.getCode();
    	valueSystem = codingDt.getSystem();
    	if (valueSystem.equals("http://hl7.org/fhir/v2/0078")) {
    		if(valueConceptCode.equals("R")) {
    			interpretation =  Interpretation.RESISTANT;
    		}	
    		if(valueConceptCode.equals("I")) {
    			interpretation =  Interpretation.INTERMEDIATE;
    		}
    		if(valueConceptCode.equals("S")) {
    			interpretation = Interpretation.SUSCEPTIBLE;
    		}
    		if(valueConceptCode.equals("POS")){
    			interpretation = Interpretation.POSITIVE;
    		}
    		if(valueConceptCode.equals("NEG")) {
    			interpretation = Interpretation.NEGATIVE;
    		}
    	}
    }
	return interpretation;
	}
	


	@Override
	public DiagnosticReport updateFHIRDiagnosticReport(DiagnosticReport diagnosticReport, String theId) {
		return diagnosticReport;
	}

	@Override
	public void retireFHIRDiagnosticReport(String id) {
	}
}
