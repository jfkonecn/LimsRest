package org.mskcc.limsrest.service;

import com.velox.api.datarecord.DataRecord;
import com.velox.api.datarecord.DataRecordManager;
import com.velox.api.datarecord.NotFound;
import com.velox.api.user.User;
import com.velox.sapioutils.client.standalone.VeloxConnection;
import com.velox.sloan.cmo.recmodels.SampleModel;
import com.velox.sloan.cmo.recmodels.SeqAnalysisSampleQCModel;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.mskcc.limsrest.ConnectionLIMS;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.rmi.RemoteException;
import java.util.*;

/**
 *
 */
public class GetSampleManifestTask {
    private static Log log = LogFactory.getLog(GetSampleManifestTask.class);

    private ConnectionLIMS conn;

    protected String [] igoIds;

    public GetSampleManifestTask(String [] igoIds, ConnectionLIMS conn) {
        this.igoIds = igoIds;
        this.conn = conn;
    }

    public static class SampleManifestResult {
        public List<SampleManifest> smList;
        public String error = null;

        public SampleManifestResult(List<SampleManifest> smList, String error) {
            this.smList = smList;
            this.error = error;
        }
    }

    /**
     * Any version of IMPACT, HemePact, ACCESS or Whole Exome
     * @param recipe
     * @return
     */
    public static boolean isPipelineRecipe(String recipe) {
        if (recipe == null)
            return false;
        // WES = 'WholeExomeSequencing', 'AgilentCapture_51MB', 'IDT_Exome_v1_FP', 'Agilent_v4_51MB_Human'
        if (recipe.contains("PACT") || recipe.contains("ACCESS") || recipe.contains("Exome") || recipe.contains("51MB")) {
            return true;
        }
        return false;
    }

    public SampleManifestResult execute() {
        long startTime = System.currentTimeMillis();

        VeloxConnection vConn = conn.getConnection();
        User user = vConn.getUser();
        DataRecordManager dataRecordManager = vConn.getDataRecordManager();

        try {
            List<SampleManifest> smList = new ArrayList<>();
            for (String igoId : igoIds) {
                smList.add(getSampleManifest(igoId, user, dataRecordManager));
            }
            log.info("Manifest generation time(ms):" + (System.currentTimeMillis() - startTime));
            return new SampleManifestResult(smList, null);
        } catch (Exception e) {
            e.printStackTrace();
            log.error(e.getMessage());
            return null;
        }
    }

    /**
     * 06260_G_128 Currently Failing because archive has 06260_G_128_1_1
     * @param igoId
     * @param user
     * @param dataRecordManager
     * @return
     * @throws Exception
     */
    protected SampleManifest getSampleManifest(String igoId, User user, DataRecordManager dataRecordManager)
            throws Exception {
        log.info("Creating sample manifest for IGO ID:" + igoId);
        List<DataRecord> sampleCMOInfoRecords = dataRecordManager.queryDataRecords("SampleCMOInfoRecords", "SampleId = '" + igoId +  "'", user);

        log.info("Searching Sample table for SampleId ='" + igoId + "'");
        List<DataRecord> samples = dataRecordManager.queryDataRecords("Sample", "SampleId = '" + igoId + "'", user);
        if (samples.size() == 0) { // sample not found in sample table
            return new SampleManifest();
        }
        DataRecord sample = samples.get(0);
        String recipe = sample.getStringVal(SampleModel.RECIPE, user);
        // for example 07951_S_50_1 is Fingerprinting sample, skip for pipelines for now
        if ("Fingerprinting".equals(recipe) || !isPipelineRecipe(recipe))
            return new SampleManifest();

        List<DataRecord> qcs = sample.getDescendantsOfType(SeqAnalysisSampleQCModel.DATA_TYPE_NAME, user);
        Set<String> runPassedQC = new HashSet<>();
        for (DataRecord dr : qcs) {
            String qcResult = dr.getStringVal(SeqAnalysisSampleQCModel.SEQ_QCSTATUS, user);
            if ("Passed".equals(qcResult)) {
                String run = dr.getStringVal(SeqAnalysisSampleQCModel.SEQUENCER_RUN_FOLDER, user);
                runPassedQC.add(run);
                log.info("Passed sample & run: " + run);
            }
        }

        // 06302_R_1 has no sampleCMOInfoRecord so use the same fields at the sample level
        DataRecord cmoInfo;  // assign the dataRecord to query either sample table or samplecmoinforecords
        if (sampleCMOInfoRecords.size() == 0) {
            cmoInfo = samples.get(0);
        } else {
            cmoInfo = sampleCMOInfoRecords.get(0);
        }

        SampleManifest sampleManifest = getSampleLevelFields(igoId, cmoInfo, user);

        // library concentration & volume
        // often null in samplecmoinforecords then query KAPALibPlateSetupProtocol1.TargetMassAliq1
        //String dmpLibraryInput = cmoInfo.getStringVal("DMPLibraryInput", user); // often null
        //String dmpLibraryOutput = cmoInfo.getStringVal("DMPLibraryOutput", user); // LIBRARY_YIELD

        List<DataRecord> aliquots = sample.getDescendantsOfType("Sample", user);
        // 07260 Request the first sample are DNA Libraries like 07260_1 so can't just search descendants to find DNA libraries
        aliquots.add(sample);
        Map<String, DataRecord> dnaLibraries = findDNALibraries(aliquots, user);

        log.info("DNA Libraries found: " + dnaLibraries.size());
        if (dnaLibraries.size() == 0) {
            // 05500_FQ_1 was submitted as a pooled library, try to find fastqs
            log.info("No DNA libraries found, searching from base IGO ID.");
            dnaLibraries.put(igoId, sample);
        }
        // for each DNA Library traverse the records grab the fields we need and paths to fastqs.
        for (Map.Entry<String, DataRecord> aliquotEntry : dnaLibraries.entrySet()) {
            String libraryIgoId = aliquotEntry.getKey();
            DataRecord aliquot = aliquotEntry.getValue();
            log.info("Processing library: " + libraryIgoId);

            DataRecord[] libPrepProtocols = aliquot.getChildrenOfType("DNALibraryPrepProtocol3", user);
            Double libraryVolume = null;
            if (libPrepProtocols != null && libPrepProtocols.length == 1)
                libraryVolume = libPrepProtocols[0].getDoubleVal("ElutionVol", user);
            Double libraryConcentration = null;
            Object libraryConcentrationObj = aliquot.getValue("Concentration", user);
            if (libraryConcentrationObj != null)  // for example 06449_1 concentration is null
                libraryConcentration = aliquot.getDoubleVal("Concentration", user);


            SampleManifest.Library library =
                    new SampleManifest.Library(libraryIgoId, libraryVolume, libraryConcentration);

            List<DataRecord> indexBarcodes = aliquot.getDescendantsOfType("IndexBarcode", user);
            DataRecord aliquotParent = null;
            if (aliquotParent != null) { // TODO get barcodes for WES samples
                // parent DNA library may have the barcode records
                indexBarcodes = aliquotParent.getDescendantsOfType("IndexBarcode", user);
            }
            if (indexBarcodes != null && indexBarcodes.size() > 0) {
                DataRecord bc = indexBarcodes.get(0);
                library.barcodeId = bc.getStringVal("IndexId", user);
                library.barcodeIndex = bc.getStringVal("IndexTag", user);
            }

            // recipe, capture input, capture name
            List<DataRecord> nimbleGen = aliquot.getDescendantsOfType("NimbleGenHybProtocol", user);
            log.info("Found nimbleGen records: " + nimbleGen.size());
            for (DataRecord n : nimbleGen) {
                Object valid = n.getValue("Valid", user); // 05359_B_1 null
                if (valid != null && new Boolean(valid.toString())) {
                    String poolName = n.getStringVal("Protocol2Sample", user);
                    String baitSet = n.getStringVal("Recipe", user); // LIMS display name "bait set"
                    sampleManifest.setBaitSet(baitSet);
                    Object val = n.getValue("SourceMassToUse", user);
                    if (val != null) {
                        Double captureInput = n.getDoubleVal("SourceMassToUse", user);
                        library.captureInputNg = captureInput.toString();
                        library.captureName = poolName;
                        Double captureVolume = n.getDoubleVal("VolumeToUse", user);
                        library.captureConcentrationNm = captureVolume.toString();
                    }
                } else {
                    log.warn("Nimblegen records not valid.");
                }
            }

            // for each flow cell ID a sample may be on multiple lanes
            // (currently all lanes are demuxed to same fastq file)
            Map<String, SampleManifest.Run> runsMap = new HashMap<>();
            // run Mode, runId, flow Cell & Lane Number
            // Flow Cell Lanes are far down the sample/pool hierarchy in LIMS
            List<DataRecord> reqLanes = aliquot.getDescendantsOfType("FlowCellLane", user);
            for (DataRecord flowCellLane : reqLanes) {
                Integer laneNum = ((Long) flowCellLane.getLongVal("LaneNum", user)).intValue();
                log.info("Reviewing flow cell lane: " + laneNum);
                List<DataRecord> flowcell = flowCellLane.getParentsOfType("FlowCell", user);
                if (flowcell.size() > 0) {
                    log.info("Getting a flow cell");
                    List<DataRecord> possibleRun = flowcell.get(0).getParentsOfType("IlluminaSeqExperiment", user);
                    if (possibleRun.size() > 0) {
                        DataRecord seqExperiment = possibleRun.get(0);
                        String runMode = seqExperiment.getStringVal("SequencingRunMode", user);
                        String flowCellId = seqExperiment.getStringVal("FlowcellId", user);
                        // TODO NOTE: ReadLength blank in LIMS prior to April 2019
                        String readLength = seqExperiment.getStringVal("ReadLength", user);

                        // TODO function to convert Illumna yymmdd date as yyyy-MM-dd ?
                        // example: /ifs/pitt/161102_PITT_0089_AHFG3GBBXX/ or /ifs/lola/150814_LOLA_1298_BC7259ACXX/
                        String run = seqExperiment.getStringVal("SequencerRunFolder", user);
                        String[] runFolderElements = run.split("_");
                        String runId = runFolderElements[1] + "_" + runFolderElements[2];
                        String runName = runId + "_" + runFolderElements[3];
                        runName = runName.replace("/", ""); // now PITT_0089_AHFG3GBBXX
                        String illuminaDate = runFolderElements[0].substring(runFolderElements[0].length() - 6); // yymmdd
                        String dateCreated = "20" + illuminaDate.substring(0, 2) + "-" + illuminaDate.substring(2, 4) + "-" + illuminaDate.substring(4, 6);

                        SampleManifest.Run r = new SampleManifest.Run(runMode, runId, flowCellId, readLength, dateCreated);
                        if (runsMap.containsKey(flowCellId)) { // already created, just add new lane num to list
                            runsMap.get(flowCellId).addLane(laneNum);
                        } else { // lookup fastq paths for this run, currently making extra queries for 06260_N_9 KIM & others
                            String fastqName = sampleManifest.getInvestigatorSampleId() + "_IGO_" + sampleManifest.getIgoId();
                            List<String> fastqs = FastQPathFinder.search(runId, fastqName, true, runPassedQC);
                            if (fastqs == null && aliquot.getLongVal("DateCreated", user) < 1455132132000L) { // try search again with pre-Jan 2016 naming convention, 06184_4
                                log.info("Searching fastq database again for pre-Jan. 2016 sample.");
                                fastqs = FastQPathFinder.search(runId, sampleManifest.getInvestigatorSampleId(), false, runPassedQC);
                            }

                            if (fastqs != null) {
                                r.addLane(laneNum);
                                r.fastqs = fastqs;

                                runsMap.put(flowCellId, r);
                                library.runs.add(r);
                            }
                        }
                    }
                }
            }

            // only report this library if it made it to a sequencer/run and has passed fastqs
            // for example 05257_BS_20 has a library which was sequenced then failed so skip
            if (library.hasFastqs()) {
                List<SampleManifest.Library> libraries = sampleManifest.getLibraries();
                libraries.add(library);
            }
        }
        return sampleManifest;
    }

    protected SampleManifest getSampleLevelFields(String igoId, DataRecord cmoInfo, User user) throws NotFound, RemoteException {
        SampleManifest s = new SampleManifest();
        s.setIgoId(igoId);
        s.setCmoPatientId(cmoInfo.getStringVal("CmoPatientId", user));
        // aka "Sample Name" in SampleCMOInfoRecords
        s.setInvestigatorSampleId(cmoInfo.getStringVal("OtherSampleId", user));
        String tumorOrNormal = cmoInfo.getStringVal("TumorOrNormal", user);
        s.setTumorOrNormal(tumorOrNormal);
        if ("Tumor".equals(tumorOrNormal))
            s.setOncoTreeCode(cmoInfo.getStringVal("TumorType", user));
        s.setTissueLocation(cmoInfo.getStringVal("TissueLocation", user));
        s.setSampleOrigin(cmoInfo.getStringVal("SampleOrigin", user)); // formerly reported as Sample Type
        s.setPreservation(cmoInfo.getStringVal("Preservation", user));
        s.setCollectionYear(cmoInfo.getStringVal("CollectionYear", user));
        s.setSex(cmoInfo.getStringVal("Gender", user));
        s.setSpecies(cmoInfo.getStringVal("Species", user));
        s.setCmoSampleName(cmoInfo.getStringVal("CorrectedCMOID", user));

        return s;
    }

    private Map<String, DataRecord> findDNALibraries(List<DataRecord> aliquots, User user) throws Exception {
        Map<String, DataRecord> dnaLibraries = new HashMap<>();
        for (DataRecord aliquot : aliquots) {
            String sampleType = aliquot.getStringVal("ExemplarSampleType", user);
            // VERY IMPORTANT, if no DNA LIBRARY NO RESULT generated
            if ("DNA Library".equals(sampleType)) {
                String libraryIgoId = aliquot.getStringVal("SampleId", user);

                String sampleStatus = aliquot.getStringVal("ExemplarSampleStatus", user);
                if (sampleStatus != null && sampleStatus.contains("Failed")) {
                    log.info("Skipping failed libarary: " + libraryIgoId);
                    continue;
                }

                String recipe = aliquot.getStringVal(SampleModel.RECIPE, user);
                if ("Fingerprinting".equals(recipe)) // for example 07951_AD_1_1
                    continue;

                log.info("Found DNA library: " + libraryIgoId);
                dnaLibraries.put(libraryIgoId, aliquot);
            }
        }

        Map<String, DataRecord> dnaLibrariesFinal = new HashMap<>();
        // For example: 09245_E_21_1_1_1, 09245_E_21_1_1_1_2 & Failed 09245_E_21_1_1_1_1
        for (String libraryName : dnaLibraries.keySet()) {
            if (!dnaLibraries.containsKey(libraryName + "_1") &&
                    !dnaLibraries.containsKey(libraryName + "_2")) {
                dnaLibrariesFinal.put(libraryName, dnaLibraries.get(libraryName));
            }
        }
        return dnaLibrariesFinal;
    }

    /**
     * Returns null if no fastqs found.
     */
    public static class FastQPathFinder {
        // TODO url to properties & make interface for FastQPathFinder
        public static List<String> search(String run,
                                          String sample_IGO_igoid,
                                          boolean returnOnlyTwo,
                                          Set<String> runPassedQC) {
            String url = "http://delphi.mskcc.org:8080/ngs-stats/rundone/search/most/recent/fastqpath/" + run + "/" + sample_IGO_igoid;
            log.info("Finding fastqs in fastq DB for: " + url);

            try {
                // some fingerprinting samples like 08390_D_73 excluded here by searching for fastqs and failing to
                // find any

                RestTemplate restTemplate = new RestTemplate();
                ResponseEntity<List<ArchivedFastq>> response = restTemplate.exchange(
                        url,
                        HttpMethod.GET,
                        null,
                        new ParameterizedTypeReference<List<ArchivedFastq>>() {});
                List<ArchivedFastq> fastqList = response.getBody();
                if (fastqList == null) {
                    log.info("NO fastqs found for run: " + run);
                    return null;
                }
                log.info("Fastq files found: " + fastqList.size());

                // 06000_FD_7 -- same run passed MICHELLE_0098_BHJCFJDMXX_A1 & failed MICHELLE_0098_BHJCFJDMXX_A2
                // so compare only full run directory to exclude failed runs
                List<ArchivedFastq> passedQCList = new ArrayList<>();
                for (ArchivedFastq fastq : fastqList) {
                    if (runPassedQC.contains(fastq.runBaseDirectory))
                        passedQCList.add(fastq);
                }

                if (returnOnlyTwo) {
                    // Return only most recent R1&R2 in case of re-demux
                    if (passedQCList.size() > 2)
                        passedQCList = passedQCList.subList(0,2);
                }

                List<String> result = new ArrayList<>();
                for (ArchivedFastq fastq : passedQCList) {
                    result.add(fastq.fastq);
                }

                if (result.size() == 0) {
                    log.info("NO passed fastqs found for run: " + run);
                    return null;
                }
                return result;
            } catch (Exception e) {
                log.error("FASTQ Search error:" + e.getMessage());
                return null;
            }
        }
    }
}