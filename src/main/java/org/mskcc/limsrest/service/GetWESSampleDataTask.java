package org.mskcc.limsrest.service;

import com.velox.api.datarecord.DataRecord;
import com.velox.api.datarecord.DataRecordManager;
import com.velox.api.datarecord.IoError;
import com.velox.api.datarecord.NotFound;
import com.velox.api.servermanager.PickListManager;
import com.velox.api.user.User;
import com.velox.sapioutils.client.standalone.VeloxConnection;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.mskcc.limsrest.ConnectionLIMS;
import org.mskcc.limsrest.service.sampletracker.WESSampleData;

import javax.net.ssl.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.rmi.RemoteException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

import static org.mskcc.limsrest.util.Utils.getValueFromDataRecord;
import static org.mskcc.limsrest.util.Utils.isSequencingComplete;

/**
 * Task to compile data for the delphi-sample-tracker app. This endpoint currently collects data for all the entries in DMPSampleTracker for "WholeExomeSequencing" and retrieves information from
 * CVR db, oncotree db and LIMS.
 *
 * @author sharmaa1
 */
public class GetWESSampleDataTask {
    private final List<String> HISEQ_2000_MACHINE_NAMES = Arrays.asList("LIZ", "LOLA");
    private final List<String> HISEQ_2500_MACHINE_NAMES = Arrays.asList("KIM", "MOMO");
    private final List<String> HISEQ_4000_MACHINE_NAMES = Arrays.asList("PITT", "JAX", "BRAD");
    private final List<String> MISEQ_MACHINE_NAMES = Arrays.asList("AYYAN", "JOHNSAWYERS", "TOMS", "VIC");
    private final List<String> NOVASEQ_MACHINE_NAMES = Arrays.asList("MICHELLE", "DIANA", "A00227", "A00333");
    private final List<String> NEXTSEQ_MACHINE_NAMES = Arrays.asList("SCOTT");
    private final List<String> SAMPLETYPES_IN_ORDER = Arrays.asList("dna", "rna", "cdna", "amplicon", "dna library", "cdnalibrary", "pooled library");
    private final List<String> NUCLEIC_ACID_TYPES = Arrays.asList("dna", "rna", "cfdna", "amplicon", "cdna");
    private final List<String> LIBRARY_SAMPLE_TYPES = Arrays.asList("dna library", "cdna library", "pooled library");

    private Log log = LogFactory.getLog(GetWESSampleDataTask.class);
    private String timestamp;
    private ConnectionLIMS conn;
    private User user;
    DataRecordManager dataRecordManager;
    PickListManager pickListManager;
    private String baitSet = "";

    private List<String> VALID_RECIPES;
    public GetWESSampleDataTask(String timestamp, ConnectionLIMS conn) {
        this.timestamp = timestamp;
        this.conn = conn;
    }

    public List<WESSampleData> execute() {
        long start = System.currentTimeMillis();
        try {
            VeloxConnection vConn = conn.getConnection();
            user = vConn.getUser();
            dataRecordManager = vConn.getDataRecordManager();
            pickListManager = vConn.getDataMgmtServer().getPickListManager(user);
            VALID_RECIPES = pickListManager.getPickListConfig("Whole-Exome Recipes for Sample Tracker").getEntryList();
            log.info(VALID_RECIPES);
            log.info(" Starting GetWesSample task using timestamp " + timestamp);
            List<DataRecord> dmpTrackerRecords = new ArrayList<>();
            try {
                dmpTrackerRecords = dataRecordManager.queryDataRecords("DMPSampleTracker", "i_SampleTypeTumororNormal='Tumor' AND DateCreated > " + Long.parseLong(timestamp) + " AND i_SampleDownstreamApplication LIKE '%Exome%' COLLATE utf8_general_ci", user);
                log.info("Num dmpTracker Records: " + dmpTrackerRecords.size());
            } catch (Throwable e) {
                log.error(e.getMessage(), e);
                return null;
            }
            List<WESSampleData> resultList = new ArrayList<>();
            JSONObject consentAList = getConsentStatusDataValues("parta");
            JSONObject consentCList = getConsentStatusDataValues("partc");
            if (!dmpTrackerRecords.isEmpty()) {
                for (DataRecord dmpTrackRec : dmpTrackerRecords) {
                    List<DataRecord> sampleCmoInfoRecs = new ArrayList<>();
                    if (dmpTrackRec.getValue("i_StudySampleIdentifierInvesti", user) != null) {
                        sampleCmoInfoRecs = dataRecordManager.queryDataRecords("SampleCMOInfoRecords", "UserSampleID = '" + dmpTrackRec.getStringVal("i_StudySampleIdentifierInvesti", user) + "'", user);
                        log.info("sample cmo info query end");
                    }
                    if (sampleCmoInfoRecs.size() > 0) {
                        for (DataRecord cmoInfoRec : sampleCmoInfoRecs) {
                            DataRecord parentSamp = cmoInfoRec.getParentsOfType("Sample", user).get(0);
                            List<DataRecord> allSamplesSharingCmoInfoRec = getChildSamplesWithRequestAsParent(parentSamp);
                            log.info("Total Wes Samples for shared CmoInfo Rec: " + allSamplesSharingCmoInfoRec.size());
                            if (allSamplesSharingCmoInfoRec.size()>0){
                                for (DataRecord sample: allSamplesSharingCmoInfoRec){
                                    log.info("processing sample: " + sample.getStringVal("SampleId", user)+ ", recipe: "+ sample.getStringVal("Recipe", user));
                                    if (isValidRecipeToProcess(sample)) {
                                        DataRecord request = getRelatedRequest(sample);
                                        String sampleId = sample.getStringVal("SampleId", user);
                                        String userSampleId = dmpTrackRec.getStringVal("i_StudySampleIdentifierInvesti", user);
                                        String userSampleidHistorical = (String) getValueFromDataRecord(dmpTrackRec, "InvestigatorSampleIdHistorical", "String", user);
                                        String duplicateSample = (String) getValueFromDataRecord(dmpTrackRec, "DuplicateSample", "String", user);
                                        String wesSampleid = (String) getValueFromDataRecord(dmpTrackRec, "WesId", "String", user);
                                        String cmoSampleId = cmoInfoRec.getStringVal("CorrectedCMOID", user);
                                        String cmoPatientId = cmoInfoRec.getStringVal("CmoPatientId", user);
                                        String dmpSampleId = dmpTrackRec.getStringVal("i_DMPSampleID", user);
                                        JSONObject cvrData = getCvrData(dmpSampleId);
                                        String dmpPatientId = getCvrDataValue(cvrData, "dmp_patient_lbl");
                                        String mrn = getCvrDataValue(cvrData, "mrn");
                                        String sex = getCvrDataValue(cvrData, "gender");
                                        String sampleType = (String)getValueFromDataRecord(cmoInfoRec, "SpecimenType", "String", user);
                                        String sampleClass = getCvrDataValue(cvrData, "sample_type");
                                        String tumorType = getCvrDataValue(cvrData, "tumor_type");
                                        String parentalTumorType = getOncotreeTumorType(tumorType);
                                        String tissueSite = getCvrDataValue(cvrData, "primary_site");
                                        String molAccessionNum = getCvrDataValue(cvrData, "molecular_accession_num");
                                        String dateDmpRequest = (String) getValueFromDataRecord(dmpTrackRec, "i_DateSubmittedtoDMP", "Date", user);
                                        String dmpRequestId = dmpTrackRec.getStringVal("i_RequestReference", user);
                                        String igoRequestId = (String) getValueFromDataRecord(request, "RequestId", "String", user);
                                        String collectionYear = (String) getValueFromDataRecord(cmoInfoRec, "CollectionYear", "String", user);
                                        String dateIgoReceived = (String) getValueFromDataRecord(request, "ReceivedDate", "Date", user);
                                        String igoCompleteDate = (String) getValueFromDataRecord(request, "CompletedDate", "Date", user);
                                        String applicationRequested = (String) getValueFromDataRecord(request, "RequestName", "String", user);
                                        String sequencerType = getSequencerTypeUsed(sample);
                                        String projectTitle = (String) getValueFromDataRecord(dmpTrackRec, "i_Studyname", "String", user);
                                        String labHead = (String) getValueFromDataRecord(request, "LaboratoryHead", "String", user);
                                        String ccFund = (String) getValueFromDataRecord(dmpTrackRec, "i_FundCostCenter", "String", user);
                                        String scientificPi = (String)getValueFromDataRecord(dmpTrackRec, "i_PrimaryInvestigator", "String", user);
                                        Boolean consentPartAStatus = getConsentStatus(consentAList, dmpPatientId);
                                        Boolean consentPartCStatus = getConsentStatus(consentCList, dmpPatientId);
                                        String sampleStatus = getSampleStatus(sample, igoRequestId);
                                        log.info("sample status: " + sampleStatus);
                                        String baitsetUsed = baitSet;
                                        String accessLevel = "";
                                        String clinicalTrial = "";
                                        String sequencingSite = "";
                                        String piRequestDate = "";
                                        String pipeline = "";
                                        String tissueType = "";
                                        String collaborationCenter = "";
                                        String limsSampleRecordId = String.valueOf(sample.getLongVal("RecordId", user));
                                        String limsTrackerRecordId = String.valueOf(dmpTrackRec.getLongVal("RecordId", user));
                                        resultList.add(new WESSampleData(sampleId, userSampleId, userSampleidHistorical, duplicateSample, wesSampleid, cmoSampleId, cmoPatientId, dmpSampleId, dmpPatientId, mrn, sex, sampleType, sampleClass, tumorType, parentalTumorType, tissueSite,
                                                molAccessionNum, collectionYear, dateDmpRequest, dmpRequestId, igoRequestId, dateIgoReceived, igoCompleteDate, applicationRequested, baitsetUsed, sequencerType, projectTitle, labHead, ccFund, scientificPi,
                                                consentPartAStatus, consentPartCStatus, sampleStatus, accessLevel, clinicalTrial, sequencingSite, piRequestDate, pipeline, tissueType, collaborationCenter, limsSampleRecordId, limsTrackerRecordId));
                                    }
                                }
                            } else {
                                WESSampleData nonIgoTrackingRecord = createNonIgoTrackingRecord(dmpTrackRec, consentAList, consentCList);
                                resultList.add(nonIgoTrackingRecord);
                            }
                        }
                    } else {
                        WESSampleData nonIgoTrackingRecord = createNonIgoTrackingRecord(dmpTrackRec, consentAList, consentCList);
                        resultList.add(nonIgoTrackingRecord);
                    }
                }
            }
            log.info("Results found: " + resultList.size() + " Elapsed time (ms): " + (System.currentTimeMillis() - start));
            return resultList;
        } catch (Throwable e) {
            log.error(e.getMessage(), e);
            return null;
        }
    }

    /**
     * Create the WESSampleData record for entries in DMPSampleTracker that are not processed in IGO.
     *
     * @param dmpTrackRec
     * @param consentAList
     * @param consentCList
     * @return WESSampleData
     * @throws NotFound
     * @throws IOException
     * @throws KeyManagementException
     * @throws NoSuchAlgorithmException
     */
    private WESSampleData createNonIgoTrackingRecord(DataRecord dmpTrackRec, JSONObject consentAList, JSONObject consentCList) throws NotFound, IOException, KeyManagementException, NoSuchAlgorithmException {
        String sampleId = "";
        String userSampleId = dmpTrackRec.getStringVal("i_StudySampleIdentifierInvesti", user);;
        String userSampleidHistorical = (String) getValueFromDataRecord(dmpTrackRec, "InvestigatorSampleIdHistorical", "String", user);
        String duplicateSample = (String) getValueFromDataRecord(dmpTrackRec,"DuplicateSample", "String", user);
        String wesSampleid = (String) getValueFromDataRecord(dmpTrackRec,"WesId", "String", user);
        String cmoSampleId = "";
        String cmoPatientId = "";
        String dmpSampleId = (String) getValueFromDataRecord(dmpTrackRec,"i_DMPSampleID", "String", user);;
        if (dmpTrackRec.getValue("i_DMPSampleID", user) != null) {
            dmpSampleId = dmpTrackRec.getStringVal("i_DMPSampleID", user);
        }
        JSONObject cvrData = getCvrData(dmpSampleId);
        String dmpPatientId = getCvrDataValue(cvrData, "dmp_patient_lbl");
        String mrn = getCvrDataValue(cvrData, "mrn");
        String sex = getCvrDataValue(cvrData, "gender");
        String sampleType = "";
        String sampleClass = getCvrDataValue(cvrData, "sample_type");
        String tumorType = getCvrDataValue(cvrData, "tumor_type");
        String parentalTumorType = getOncotreeTumorType(tumorType);
        String tissueSite = getCvrDataValue(cvrData, "primary_site");
        String molAccessionNum = getCvrDataValue(cvrData, "molecular_accession_num");
        String dateDmpRequest = (String) getValueFromDataRecord(dmpTrackRec, "i_DateSubmittedtoDMP", "Date", user);
        String dmpRequestId = dmpTrackRec.getStringVal("i_RequestReference", user);
        String igoRequestId = "";
        String collectionYear = "";
        String dateIgoReceived = "";
        String igoCompleteDate = "";
        String applicationRequested = (String) getValueFromDataRecord(dmpTrackRec, "i_SampleDownstreamApplication", "String", user);
        String baitsetUsed = "";
        String sequencerType = "";
        String projectTitle = (String) getValueFromDataRecord(dmpTrackRec, "i_Studyname", "String", user);
        String labHead = (String) getValueFromDataRecord(dmpTrackRec, "i_PrimaryInvestigator", "String", user);
        String ccFund = (String) getValueFromDataRecord(dmpTrackRec, "i_FundCostCenter", "String", user);
        String scientificPi = (String)getValueFromDataRecord(dmpTrackRec, "i_PrimaryInvestigator", "String", user);
        Boolean consentPartAStatus = getConsentStatus(consentAList, dmpPatientId);
        Boolean consentPartCStatus = getConsentStatus(consentCList, dmpPatientId);
        String sampleStatus = "";
        String accessLevel = "";
        String clinicalTrial = "";
        String sequencingSite = "";
        String piRequestDate = "";
        String pipeline = "";
        String tissueType = "";
        String collaborationCenter = "";
        String limsSampleRecordId = "";
        String limsTrackerRecordId = String.valueOf(dmpTrackRec.getLongVal("RecordId", user));
        WESSampleData nonIgoTrackingRec = new WESSampleData(sampleId, userSampleId, userSampleidHistorical, duplicateSample, wesSampleid, cmoSampleId, cmoPatientId, dmpSampleId, dmpPatientId, mrn, sex, sampleType, sampleClass, tumorType, parentalTumorType, tissueSite,
                molAccessionNum, collectionYear, dateDmpRequest, dmpRequestId, igoRequestId, dateIgoReceived, igoCompleteDate, applicationRequested, baitsetUsed, sequencerType, projectTitle, labHead, ccFund, scientificPi,
                consentPartAStatus, consentPartCStatus, sampleStatus, accessLevel, clinicalTrial, sequencingSite, piRequestDate, pipeline, tissueType, collaborationCenter, limsSampleRecordId, limsTrackerRecordId);
        return nonIgoTrackingRec;
    }

    /**
     * Method to get all the child Samples directly under request as child and having a valid Whole Exome recipe.
     * @param sample
     * @return
     * @throws NotFound
     * @throws RemoteException
     * @throws IoError
     */
    private List<DataRecord> getChildSamplesWithRequestAsParent(DataRecord sample) throws NotFound, RemoteException, IoError {
        List<DataRecord> descendantSamples = sample.getDescendantsOfType("Sample", user);
        log.info("Total Descendant Samples: " + descendantSamples.size());
        List<DataRecord> sampleList = new ArrayList<>();
        if (sample.getParentsOfType("Request", user).size()>0){
            sampleList.add(sample);
        }
            if (descendantSamples.size()>0){
                for (DataRecord rec : descendantSamples){
                    Boolean sampleHasRequestAsParent = rec.getParentsOfType("Request", user).size() > 0;
                    if (sampleHasRequestAsParent){
                        sampleList.add(rec);
                    }
                }
            }
        return sampleList;
    }
    /**
     * Get Request DataRecord for a Sample.
     *
     * @param sample
     * @return DataRecord
     * @throws RemoteException
     * @throws NotFound
     */
    private DataRecord getRelatedRequest(DataRecord sample) throws RemoteException, NotFound {
        try {
            if (sample.getParentsOfType("Request", user).size() > 0) {
                return sample.getParentsOfType("Request", user).get(0);
            }
            Stack<DataRecord> sampleStack = new Stack<>();
            if (sample.getParentsOfType("Sample", user).size() > 0) {
                sampleStack.push(sample.getParentsOfType("Sample", user).get(0));
            }
            do {
                DataRecord startSample = sampleStack.pop();
                if (startSample.getParentsOfType("Request", user).size() > 0) {
                    return startSample.getParentsOfType("Request", user).get(0);
                } else if (startSample.getParentsOfType("Sample", user).size() > 0) {
                    sampleStack.push(startSample.getParentsOfType("Sample", user).get(0));
                }
            } while (!sampleStack.isEmpty());
        } catch (Exception e) {
            log.error(String.format("Error occured while finding parent Request for Sample %s\n%s", sample.getStringVal("SampleId", user), Arrays.toString(e.getStackTrace())));
        }
        return null;
    }

    /**
     * Check if the recipe on the Sample is valid to create WESSampleData Object.
     *
     * @param sample
     * @return Boolean
     * @throws NotFound
     * @throws RemoteException
     */
    private Boolean isValidRecipeToProcess(DataRecord sample) throws NotFound, RemoteException {
        String sampleId = sample.getStringVal("SampleId", user);
        try {
            Object recipe = sample.getValue("Recipe", user);
            if (recipe != null) {
                for (String rec : VALID_RECIPES) {
                    if (String.valueOf(recipe).toLowerCase().trim().equals(rec.toLowerCase().trim())) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log.error(String.format("Error occured while validating Recipe for Sample %s\n%s", sampleId, Arrays.toString(e.getStackTrace())));
        }
        return false;
    }

    /**
     * Method to get data from cvr endpoint using dmpSampleId.
     *
     * @param dmpSampleId
     * @return JSONObject
     * @throws NoSuchAlgorithmException
     * @throws KeyManagementException
     * @throws IOException
     */
    private JSONObject getCvrData(String dmpSampleId) throws NoSuchAlgorithmException, KeyManagementException, IOException {
        TrustManager[] trustAllCerts = new TrustManager[]{new X509TrustManager() {
            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return null;
            }

            public void checkClientTrusted(X509Certificate[] certs, String authType) {
            }

            public void checkServerTrusted(X509Certificate[] certs, String authType) {
            }
        }
        };
        // Install the all-trusting trust manager
        SSLContext sc = SSLContext.getInstance("SSL");
        sc.init(null, trustAllCerts, new java.security.SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

        // Create all-trusting host name verifier
        HostnameVerifier allHostsValid = new HostnameVerifier() {
            public boolean verify(String hostname, SSLSession session) {
                return true;
            }
        };
        dmpSampleId.replace(" ", "%20");
        dmpSampleId.replace("/", "%2F");
        dmpSampleId.replace("&", "%26");
        StringBuffer response = new StringBuffer();
        JSONObject cvrResponseData = new JSONObject();
        // Install the all-trusting host verifier
        try {
            HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid);
            URL url = new URL("https://draco.mskcc.org:9898/get_cmo_metadata/" + dmpSampleId);
            log.info(url.toString());
            URLConnection con = url.openConnection();
            BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();
            cvrResponseData = new JSONObject(response.toString());
        } catch (Exception e) {
            log.error(String.format("Error occured while querying CVR end point for DMP Sample ID %s\n%s", dmpSampleId, Arrays.toString(e.getStackTrace())));
        }
        return cvrResponseData;
    }

    /**
     * Get value from CVR data using Json Key.
     *
     * @param cvrResponseData
     * @param key
     * @return String
     */
    private String getCvrDataValue(JSONObject cvrResponseData, String key) {
        if (cvrResponseData.length() > 0) {
            if (cvrResponseData.has(key) && cvrResponseData.get(key) != null) {
                return (String) cvrResponseData.get(key);
            }
        }
        return "Not found";
    }

    /**
     * Method to get Main Tumor Type using Tumor Type Name eg: Breast Cancer or Pancreatic cancer etc.
     *
     * @param url
     * @param tumorType
     * @return String
     */
    private String getOncotreeTumorTypeUsingTumorName(URL url, String tumorType) {
        HttpURLConnection con = null;
        StringBuffer response = new StringBuffer();
        JSONArray oncotreeResponseData = null;
        try {
            con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();
            con.disconnect();
            oncotreeResponseData = new JSONArray(response.toString());
            if (oncotreeResponseData.length() > 0) {
                JSONObject jsonObject = oncotreeResponseData.getJSONObject(0);
                Object mainType = jsonObject.get("mainType");
                return mainType != null ? mainType.toString() : "";
            }
        } catch (Exception e) {
            log.info(String.format("Error while querying oncotree api for name search. Will attempt to search using oncotree api for code search:\n%s",e.getMessage()));
            return "";
        }
        return "";
    }

    /**
     * Method to get Main Tumor Type using TumorType CODE or abbreviation eg: BRCA for Breast Cancer and PAAD for
     * Pancreatic cancer etc.
     *
     * @param url
     * @param tumorType
     * @return String
     */
    private String getOncotreeTumorTypeUsingTumorCode(URL url, String tumorType) {
        HttpURLConnection con = null;
        StringBuffer response = new StringBuffer();
        JSONArray oncotreeResponseData = null;
        try {
            con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();
            con.disconnect();
            oncotreeResponseData = new JSONArray(response.toString());
            if (oncotreeResponseData.length() > 0) {
                for (Object rec : oncotreeResponseData) {
                    Object code = ((JSONObject) rec).get("code");
                    if (code != null && tumorType.toLowerCase().equals(code.toString().trim().toLowerCase())) {
                        Object mainType = ((JSONObject) rec).get("mainType");
                        return mainType != null ? mainType.toString() : "";
                    }
                }
            }
        } catch (Exception e) {
            log.info(String.format("Error while querying oncotree api using code search. Cannot find Main tumor type.\n%s",e.getMessage()));
            return "";
        }
        return "";
    }

    /**
     * Get MainCancerType from oncotree
     *
     * @param tumorType
     * @return String
     */
    private String getOncotreeTumorType(String tumorType) {
        StringBuffer response = new StringBuffer();
        JSONArray oncotreeResponseData = null;
        String mainTumorType = "";
        try {
            // In LIMS tumor types entry is not controlled. Sometimes tumor type as tumor name is entered and other times tumor type code is entered.
            // First query oncotree using api for name search
            URL url = new URL("http://oncotree.mskcc.org/api/tumorTypes/search/name/" + tumorType.split("/")[0].replace(" ", "%20") + "?exactMatch=false");
            mainTumorType = getOncotreeTumorTypeUsingTumorName(url, tumorType);
            // If name search returns nothing, then query oncotree using api for code search
            if (StringUtils.isBlank(mainTumorType)) {
                URL url2 = new URL("http://oncotree.mskcc.org/api/tumorTypes/search/code/" + tumorType.split("/")[0].replace(" ", "%20") + "?exactMatch=true");
                mainTumorType = getOncotreeTumorTypeUsingTumorCode(url2, tumorType);
            }
        } catch (Exception e) {
            log.error(String.format("Error occured while querying oncotree end point for Tumor Type %s\n%s", tumorType, e.getMessage()));
            return "";
        }
        return mainTumorType;
    }

    /**
     * Get the sequencer types that were used to process the samples.
     *
     * @param sample
     * @return String
     * @throws RemoteException
     * @throws NotFound
     */
    private String getSequencerTypeUsed(DataRecord sample) throws RemoteException, NotFound {
        List<DataRecord> sampleLevelSeqQcRecs = sample.getDescendantsOfType("SeqAnalysisSampleQC", user);
        Set<String> sequencerTypes = new HashSet<>();
        if (sampleLevelSeqQcRecs.size() > 0) {
            for (DataRecord record : sampleLevelSeqQcRecs) {
                if (record.getValue("SequencerRunFolder", user) != null) {
                    sequencerTypes.add(getSequencerType(record.getStringVal("SequencerRunFolder", user).split("_")[0]));
                }
            }
        }
        return String.join(",", sequencerTypes);
    }

    /**
     * Get Sequencer type based on Sequencing machine name.
     *
     * @param machineName
     * @return String
     */
    private String getSequencerType(String machineName) {
        if (HISEQ_2000_MACHINE_NAMES.contains(machineName.toUpperCase())) {
            return "HiSeq 2000";
        }
        if (HISEQ_2500_MACHINE_NAMES.contains(machineName.toUpperCase())) {
            return "HiSeq 2500";
        }
        if (HISEQ_4000_MACHINE_NAMES.contains(machineName.toUpperCase())) {
            return "HiSeq 4000";
        }
        if (MISEQ_MACHINE_NAMES.contains(machineName.toUpperCase())) {
            return "MiSeq";
        }
        if (NOVASEQ_MACHINE_NAMES.contains(machineName.toUpperCase())) {
            return "NovaSeq";
        }
        if (NEXTSEQ_MACHINE_NAMES.contains(machineName.toUpperCase())) {
            return "NextSeq";
        }
        return "NA";
    }

    /**
     * Get all the patients consent statuses for a consent type(PartA or PartC).
     *
     * @param consentType
     * @return JSONObject
     * @throws MalformedURLException
     * @throws JSONException
     */
    private JSONObject getConsentStatusDataValues(String consentType) throws MalformedURLException, JSONException {
        StringBuffer response = new StringBuffer();
        JSONObject cvrResponseData = new JSONObject();
        URL url;
        if (consentType.toLowerCase().equals("parta")) {
            url = new URL("http://draco.mskcc.org:9890/get_12245_list_parta");
        } else {
            url = new URL("http://draco.mskcc.org:9890/get_12245_list_partc");
        }
        try {
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();
            con.disconnect();
            cvrResponseData = new JSONObject(response.toString());
        } catch (Exception e) {
            log.error(String.format("Error occured while querying consent '%s' end point.\n", consentType));
        }
        return cvrResponseData.getJSONObject("cases");
    }

    /**
     * Get consent status for a patient.
     *
     * @param consentData
     * @param patientId
     * @return Boolean
     */
    private Boolean getConsentStatus(JSONObject consentData, String patientId) {
        if (consentData.has(patientId)) {
            return (Boolean) consentData.get(patientId);
        }
        return false;
    }

    /**
     * Method to get latest sample status.
     * @param sample
     * @param requestId
     * @return
     */
    private String getSampleStatus(DataRecord sample, String requestId){
        String sampleId ="";
        String sampleStatus;
        String currentSampleType = "";
        String currentSampleStatus = "";
        try{
            sampleId = sample.getStringVal("SampleId", user);
            sampleStatus = (String)getValueFromDataRecord(sample, "ExemplarSampleStatus", "String", user);
            int statusOrder=-1;
            long recordId = 0;
            Stack<DataRecord> sampleStack = new Stack<>();
            sampleStack.add(sample);
            do{
                DataRecord current = sampleStack.pop();
                currentSampleType = (String)getValueFromDataRecord(current, "ExemplarSampleType", "String", user);
                currentSampleStatus = (String)getValueFromDataRecord(current, "ExemplarSampleStatus", "String", user);
                int currentStatusOrder = SAMPLETYPES_IN_ORDER.indexOf(currentSampleType.toLowerCase());
                long currentRecordId = current.getRecordId();
                if (isSequencingComplete(current, user)){
                    return "Completed Sequencing";
                }
                if (currentRecordId > recordId && currentStatusOrder > statusOrder && isCompleteStatus(currentSampleStatus)){
                    sampleStatus = currentSampleStatus;
                    recordId = currentRecordId;
                    statusOrder= currentStatusOrder;
                }
                DataRecord[] childSamples = current.getChildrenOfType("Sample", user);
                for (DataRecord sam: childSamples){
                    String childRequestId = sam.getStringVal("RequestId", user);
                    if (requestId.equalsIgnoreCase(childRequestId)){
                        sampleStack.push(sam);
                    }
                }
            }while(sampleStack.size()>0);
        }catch (Exception e){
            log.error(String.format("Error while getting status for sample '%s'.", sampleId));
            return "";
        }
        return resolveCurrentStatus(sampleStatus,currentSampleType);
    }

    /**
     * Method to check is sample status is a completed status.
     * @param status
     * @return
     */
    private boolean isCompleteStatus(String status){
        return status.toLowerCase().contains("completed") || status.toLowerCase().contains("failed");
    }

    /**
     * Method to check if the sample status is equivalent to "completed sequencing".
     * @param status
     * @return
     */
    private boolean isSequencingCompleteStatus(String status){
        status = status.toLowerCase();
        return status.contains("completed - ") && status.contains("illumina") && status.contains("sequencing");
    }

    /**
     * Method to resolve the sample status to one of the main sample statuses.
     * @param status
     * @param sampleType
     * @return
     */
    private String resolveCurrentStatus(String status, String sampleType) {
        if (NUCLEIC_ACID_TYPES.contains(sampleType.toLowerCase()) && status.toLowerCase().contains("completed -") && status.toLowerCase().contains("extraction") && status.toLowerCase().contains("dna/rna simultaneous") ) {
            return String.format("Completed - %s Extraction", sampleType.toUpperCase());
        }
        if (NUCLEIC_ACID_TYPES.contains(sampleType.toLowerCase()) && status.toLowerCase().contains("completed -") && status.toLowerCase().contains("extraction") && status.toLowerCase().contains("rna") ) {
            return "Completed - RNA Extraction";
        }
        if (NUCLEIC_ACID_TYPES.contains(sampleType.toLowerCase()) && status.toLowerCase().contains("completed -") && status.toLowerCase().contains("extraction") && status.toLowerCase().contains("dna") ) {
            return "Completed - DNA Extraction";
        }
        if (NUCLEIC_ACID_TYPES.contains(sampleType.toLowerCase()) && status.toLowerCase().contains("completed -") && status.toLowerCase().contains("quality control")) {
            return "Completed - Quality Control";
        }
        if (LIBRARY_SAMPLE_TYPES.contains(sampleType.toLowerCase()) && status.toLowerCase().contains("completed") && status.toLowerCase().contains("library preparation")) {
            return "Completed - Library Preparaton";
        }
        if (LIBRARY_SAMPLE_TYPES.contains(sampleType.toLowerCase()) && isSequencingCompleteStatus(status)){
            return "Completed - Sequencing";
        }
        if (status.toLowerCase().contains("failed")){
            return status;
        }
        return "";
    }
}
