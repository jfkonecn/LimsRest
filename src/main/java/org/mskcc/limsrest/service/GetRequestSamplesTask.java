package org.mskcc.limsrest.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.velox.api.datarecord.DataRecord;
import com.velox.api.datarecord.DataRecordManager;
import com.velox.api.user.User;
import com.velox.sapioutils.client.standalone.VeloxConnection;
import com.velox.sloan.cmo.recmodels.RequestModel;
import com.velox.sloan.cmo.recmodels.SampleModel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.mskcc.limsrest.ConnectionLIMS;
import org.mskcc.limsrest.model.RequestSample;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 *
 */
public class GetRequestSamplesTask {
    private static Log log = LogFactory.getLog(GetRequestSamplesTask.class);

    private ConnectionLIMS conn;
    private String requestId;

    public GetRequestSamplesTask(String requestId, ConnectionLIMS conn) {
        this.requestId = requestId;
        this.conn = conn;
    }

    public GetRequestSamplesTask.RequestSampleList execute() {
        try {
            VeloxConnection vConn = conn.getConnection();
            User user = vConn.getUser();
            DataRecordManager drm = vConn.getDataRecordManager();

            List<DataRecord> requestList = drm.queryDataRecords("Request", "RequestId = '" + this.requestId + "'", user);
            if (requestList.size() != 1) {  // error: request ID not found or more than one found
                log.error("Request not found:" + requestId);
                return new RequestSampleList("NOT_FOUND");
            }

            // get set of all samples in that request that are "IGO Complete"
            HashSet<String> samplesIGOComplete = getSamplesIGOComplete(requestId, user, drm);
            log.info("Samples IGO Complete: " + samplesIGOComplete.size());

            DataRecord requestDataRecord = requestList.get(0);
            DataRecord[] samples = requestDataRecord.getChildrenOfType("Sample", user);
            log.info("Child samples found: " + samples.length);

            List<RequestSample> sampleList = new ArrayList<>();
            String recipe = "";
            for (DataRecord sample : samples) {
                String igoId = sample.getStringVal("SampleId", user);
                String sampleRecipe = sample.getStringVal(SampleModel.RECIPE, user);
                if ("Fingerprinting".equals(sampleRecipe)) // for example 07951_S_50_1, skip for pipelines for now
                    continue;
                else
                    recipe = sampleRecipe;
                String othersampleId = sample.getStringVal("OtherSampleId", user);
                boolean igoComplete = samplesIGOComplete.contains(othersampleId);
                // same othersampleId as other samples but these failed, could check exemplarSampleStatus too
                // remove if qc status lookup done by IGO ID
                if ("07078_E_1".equals(igoId) || "07078_E_2".equals(igoId) || "07078_E_5".equals(igoId))
                    igoComplete = false;

                RequestSample rs = new RequestSample(othersampleId, igoId, igoComplete);
                sampleList.add(rs);
            }
            RequestSampleList rsl = new RequestSampleList(requestId, sampleList);

            if (isIMPACTOrHEMEPACT(recipe)) {
                log.info("Adding pooled normals for recipe: " + recipe);
                rsl.setPooledNormals(findPooledNormals(requestId));
            }
            String requestName = requestDataRecord.getStringVal(RequestModel.REQUEST_NAME, user);
            if (requestName != null && requestName.toUpperCase().contains("RNASEQ")) {
                setRNASeqLibraryTypeAndStrandedness(rsl, requestName);
            }
            rsl.setRecipe(recipe);
            rsl.setPiEmail(requestDataRecord.getStringVal("PIemail", user));
            rsl.setLabHeadName(requestDataRecord.getStringVal("LaboratoryHead", user));
            rsl.setLabHeadEmail(requestDataRecord.getStringVal("LabHeadEmail", user));
            rsl.setProjectManagerName(requestDataRecord.getStringVal("ProjectManager", user));
            rsl.setInvestigatorName(requestDataRecord.getStringVal("Investigator", user));
            rsl.setInvestigatorEmail(requestDataRecord.getStringVal("Investigatoremail", user));
            rsl.setDataAnalystName(requestDataRecord.getStringVal("DataAnalyst", user));
            rsl.setDataAnalystEmail(requestDataRecord.getStringVal("DataAnalystEmail", user));
            rsl.setOtherContactEmails(requestDataRecord.getStringVal("MailTo", user));
            rsl.setQcAccessEmails(requestDataRecord.getStringVal("QcAccessEmails", user));
            rsl.setDataAccessEmails(requestDataRecord.getStringVal("DataAccessEmails", user));

            // GetRequestPermissionsTask will set fastq.gz permissions based on whether or not BIC or CMO request so
            // return those values here too.
            // alternatively, IGO could tell people to call the GetRequestPermissions endpoint instead of adding
            // the fields here too.
            Boolean isCmoRequest = Boolean.FALSE;
            Boolean bicAnalysis = Boolean.FALSE;
            String analysisType = "";
            try {
                isCmoRequest = requestDataRecord.getBooleanVal("IsCmoRequest", user);
                bicAnalysis = requestDataRecord.getBooleanVal("BICAnalysis", user);
                analysisType = requestDataRecord.getStringVal("AnalysisType", user);
            } catch (NullPointerException e) {
                log.warn("Correct invalid null valid in database for request: " + requestId);
            }
            Boolean isBicRequest = GetRequestPermissionsTask.isBicRequest(analysisType, bicAnalysis);

            rsl.setBicAnalysis(isBicRequest);
            rsl.setCmoRequest(isCmoRequest);

            return rsl;
        } catch (Throwable e) {
            log.error(e.getMessage(), e);
            return null;
        }
    }

    protected void setRNASeqLibraryTypeAndStrandedness(RequestSampleList rsl, String requestName) {
        // this classification by requestname will not be correct for all historical requests
        // customer has stated they already have all historical values and only need Nov. 2019-on to work

        // RNASeq-SMARTerAmp is non-stranded
        // RNASeq-TruSeqPolyA & RNASeq-TruSeqRiboDeplete is stranded-reverse
        // ignore RNAExtraction & RNA-QC request names
        String requestNameUpper = requestName.toUpperCase();
        if (requestNameUpper.contains("SMARTER"))
            rsl.setStrand("non-stranded");
        else // as of 2019 IGO has no "stranded-forward" kits.
            rsl.setStrand("stranded-reverse");

        // The LibraryType field was included in the original Pipelinekickoff codebase and BIC requested that
        // IGO add it to the endpoint in August 2020
        rsl.setLibraryType(requestName);
    }

    /**
     * Pooled normals were added to IMPACT requests up to IMPACT505 and used by the pipeline for
     * sample pairing when the patient's normal sample was not present.
     * @param recipe
     * @return
     */
    protected static boolean isIMPACTOrHEMEPACT(String recipe) {
        if (recipe.isEmpty())
            return false;
        if ("IMPACT341,IMPACT410,IMPACT410+,IMPACT468,IMPACT505,HemePACT_v3,HemePACT_v4".contains(recipe))
            return true;
        return false;
    }

    /**
     * Returns set of all samples IGO Complete by seqanalysissampleqc.othersampleid
     * @param requestId
     * @return
     * @throws Exception
     */
    protected HashSet<String> getSamplesIGOComplete(String requestId, User user, DataRecordManager drm) throws Exception {
        String whereClause = "PassedQC = 1 AND SeqQCStatus = 'Passed' AND Request = '" + requestId + "'";
        List<DataRecord> listIGOComplete = drm.queryDataRecords("SeqAnalysisSampleQC", whereClause, user);
        HashSet<String> samplesIGOComplete = new HashSet<>();
        for (DataRecord r : listIGOComplete) {
            samplesIGOComplete.add(r.getStringVal("OtherSampleId", user));
        }
        return samplesIGOComplete;
    }

    /*
    Finds all pooled normals included on any run for a given request.
     */
    public static List<String> findPooledNormals(String request) {
        // TODO
        String url = "http://delphi.mskcc.org:8080/ngs-stats/rundone/getpoolednormals/" + request;
        log.info("Finding pooled normal fastqs in fastq DB for: " + url);

        try {
            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<List<ArchivedFastq>> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<List<ArchivedFastq>>() {
                    });
            List<ArchivedFastq> fastqList = response.getBody();
            List<String> fastqPaths = new ArrayList<>();
            for (ArchivedFastq fastq : fastqList) {
                fastqPaths.add(fastq.getFastq());
            }
            return fastqPaths;
        } catch (Exception e) {
            log.error("FASTQ Search error:" + e.getMessage());
            return null;
        }
    }

    public static class RequestSampleList {
        public String requestId;
        public String recipe;
        public String projectManagerName;
        public String piEmail;
        public String labHeadName, labHeadEmail;
        public String investigatorName, investigatorEmail;
        public String dataAnalystName, dataAnalystEmail;
        public String otherContactEmails;
        public String dataAccessEmails;
        public String qcAccessEmails;
        public String strand; // only for RNASeq
        public String libraryType; // only for RNASeq
        public Boolean isCmoRequest;
        public Boolean bicAnalysis;

        public List<RequestSample> samples;

        protected List<String> pooledNormals;

        public RequestSampleList(){}

        public RequestSampleList(String requestId){ this.requestId = requestId; }

        public RequestSampleList(String requestId, List<RequestSample> samples) {
            this.requestId = requestId;
            this.samples = samples;
        }

        public Boolean getCmoRequest() { return isCmoRequest; }
        public void setCmoRequest(Boolean cmoRequest) { isCmoRequest = cmoRequest; }

        public Boolean getBicAnalysis() { return bicAnalysis; }
        public void setBicAnalysis(Boolean bicAnalysis) { this.bicAnalysis = bicAnalysis; }

        public String getLibraryType() { return libraryType; }
        public void setLibraryType(String libraryType) { this.libraryType = libraryType; }

        public String getStrand() { return strand; }
        public void setStrand(String strand) { this.strand = strand; }

        public String getRecipe() { return recipe; }
        public void setRecipe(String recipe) { this.recipe = recipe; }

        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public String getRequestId() {
            return requestId;
        }
        public void setRequestId(String requestId) {
            this.requestId = requestId;
        }

        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public List<RequestSample> getSamples() {
            return samples;
        }
        public void setSamples(List<RequestSample> samples) {
            this.samples = samples;
        }

        public List<String> getPooledNormals() {
            return pooledNormals;
        }

        public void setPooledNormals(List<String> pooledNormals) {
            this.pooledNormals = pooledNormals;
        }

        public String getProjectManagerName() {
            return projectManagerName;
        }
        public void setProjectManagerName(String projectManagerName) {
            this.projectManagerName = projectManagerName;
        }

        public String getPiEmail() {
            return piEmail;
        }
        public void setPiEmail(String piEmail) {
            this.piEmail = piEmail;
        }

        public String getInvestigatorName() {
            return investigatorName;
        }
        public void setInvestigatorName(String investigatorName) {
            this.investigatorName = investigatorName;
        }

        public String getInvestigatorEmail() {
            return investigatorEmail;
        }
        public void setInvestigatorEmail(String investigatorEmail) {
            this.investigatorEmail = investigatorEmail;
        }

        public String getDataAnalystName() {
            return dataAnalystName;
        }
        public void setDataAnalystName(String dataAnalystName) {
            this.dataAnalystName = dataAnalystName;
        }

        public String getDataAnalystEmail() {
            return dataAnalystEmail;
        }
        public void setDataAnalystEmail(String dataAnalystEmail) {
            this.dataAnalystEmail = dataAnalystEmail;
        }

        public String getLabHeadName() {
            return labHeadName;
        }
        public void setLabHeadName(String labHeadName) {
            this.labHeadName = labHeadName;
        }

        public String getLabHeadEmail() {
            return labHeadEmail;
        }
        public void setLabHeadEmail(String labHeadEmail) {
            this.labHeadEmail = labHeadEmail;
        }

        public String getOtherContactEmails() {
            return otherContactEmails;
        }
        public void setOtherContactEmails(String otherContactEmails) {
            this.otherContactEmails = otherContactEmails;
        }

        public String getQcAccessEmails() { return qcAccessEmails; }
        public void setQcAccessEmails(String qcAccessEmails) { this.qcAccessEmails = qcAccessEmails; }

        public String getDataAccessEmails() { return dataAccessEmails; }
        public void setDataAccessEmails(String dataAccessEmails) { this.dataAccessEmails = dataAccessEmails; }
    }

}
