package org.mskcc.limsrest.service;

import com.velox.api.datarecord.DataRecord;
import com.velox.api.datarecord.DataRecordManager;
import com.velox.api.user.User;
import com.velox.sapioutils.client.standalone.VeloxConnection;
import org.junit.Test;
import org.mockito.Mockito;
import org.mskcc.limsrest.MockDataRecord;
import org.mskcc.limsrest.service.assignedprocess.QcStatus;
import org.mskcc.limsrest.service.assignedprocess.QcStatusAwareProcessAssigner;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mskcc.util.VeloxConstants.SAMPLE;

public class ToggleSampleQcStatusTest {

    @Test
    public void regularRepoolTest() throws Exception {
        /**
         * A recipe that is not custom capture should have the repool process assigned to the input sample, seqQcRecord.
         * It should NOT be assigned to the sampleRecord
         *      sampleRecord -"is [SAMPLE] Parent Of"-> seqQcRecord
         */
        String recipe = "other";
        DataRecord seqQcRecord = Mockito.mock(DataRecord.class);
        DataRecord sampleRecord = Mockito.mock(DataRecord.class);

        /* ToggleSampleQcStatus MOCKS/SPIES */
        VeloxConnection conn = Mockito.mock(VeloxConnection.class);
        DataRecordManager drmMock = Mockito.mock(DataRecordManager.class);
        User userMock = Mockito.mock(User.class);
        QcStatusAwareProcessAssigner processAssignerSpy = Mockito.spy(QcStatusAwareProcessAssigner.class);

        /* DATA RECORD MOCKS/SPIES */
        List<DataRecord> sampleParents = new ArrayList<>();
        sampleParents.add(sampleRecord);

        /* INITIALIZE TASK */
        long recordId = 5457947;
        ToggleSampleQcStatus task = new ToggleSampleQcStatus();
        task.init(recordId, "Repool-Sample", "", null, null, "Seq", null, null, null, recipe);
        task.dataRecordManager = drmMock;
        task.user = userMock;
        task.qcStatusAwareProcessAssigner = processAssignerSpy;
        Mockito.when(task.dataRecordManager.querySystemForRecord(recordId, "SeqAnalysisSampleQC", task.user)).thenReturn(seqQcRecord);
        task.execute(conn);

        Mockito.verify(processAssignerSpy, Mockito.times(0)).assign(Mockito.eq(drmMock), Mockito.eq(userMock), Mockito.eq(sampleRecord), Mockito.any(QcStatus.class));
        Mockito.verify(processAssignerSpy, Mockito.times(1)).assign(Mockito.eq(drmMock), Mockito.eq(userMock), Mockito.eq(seqQcRecord), Mockito.any(QcStatus.class));
    }

    @Test
    public void customCaptureRepoolTest() throws Exception {
        /**
         * A recipe that is not custom capture should have the repool process assigned to the input sample, seqQcRecord.
         * It should NOT be assigned to the sampleRecord
         *      sampleRecord - "is [SAMPLE] Parent Of"-> seqQcRecord
         *      sampleRecord - "is [SAMPLE] Parent Of"-> poolingProtocolRecord
         */
        String recipe = "HemePACT_v4";
        DataRecord seqQcRecord = Mockito.mock(DataRecord.class);
        DataRecord sampleRecord = Mockito.mock(DataRecord.class);
        DataRecord poolingProtocolRecord = Mockito.mock(DataRecord.class);

        /* ToggleSampleQcStatus MOCKS/SPIES */
        VeloxConnection conn = Mockito.mock(VeloxConnection.class);
        DataRecordManager drmMock = Mockito.mock(DataRecordManager.class);
        User userMock = Mockito.mock(User.class);
        QcStatusAwareProcessAssigner processAssignerSpy = Mockito.spy(QcStatusAwareProcessAssigner.class);

        /* DATA RECORD MOCKS/SPIES */
        List<DataRecord> sampleParents = new ArrayList<>();
        sampleParents.add(sampleRecord);
        DataRecord[] poolingProtocolChildren = new DataRecord[]{poolingProtocolRecord};
        Mockito.when(sampleRecord.getChildrenOfType("PoolingSampleLibProtocol", userMock)).thenReturn(poolingProtocolChildren);
        Mockito.when(seqQcRecord.getParentsOfType(Mockito.eq(SAMPLE), Mockito.any(User.class))).thenReturn(sampleParents);

        /* INITIALIZE TASK */
        long recordId = 5457947;
        ToggleSampleQcStatus task = new ToggleSampleQcStatus();
        task.init(recordId, "Repool-Sample", "", null, null, "Seq", null, null, null, recipe);
        task.dataRecordManager = drmMock;
        task.user = userMock;
        task.qcStatusAwareProcessAssigner = processAssignerSpy;
        Mockito.when(task.dataRecordManager.querySystemForRecord(recordId, "SeqAnalysisSampleQC", task.user)).thenReturn(seqQcRecord);
        // Mockito.doNothing().when(processAssignerSpy).assign(Mockito.eq(drmMock), Mockito.eq(userMock),Mockito.any(DataRecord.class),Mockito.any(QcStatus.class));
        task.execute(conn);

        Mockito.verify(processAssignerSpy, Mockito.times(1)).assign(Mockito.eq(drmMock), Mockito.eq(userMock), Mockito.eq(sampleRecord), Mockito.any(QcStatus.class));
        Mockito.verify(processAssignerSpy, Mockito.times(0)).assign(Mockito.eq(drmMock), Mockito.eq(userMock), Mockito.eq(seqQcRecord), Mockito.any(QcStatus.class));
    }

    @Test
    public void setSeqAnalysisSampleQcStatusPassed() throws Exception {
        DataRecord seqQc = new DataRecord(0, "QC", new MockDataRecord());
        QcStatus complete = QcStatus.PASSED;

        ToggleSampleQcStatus.setSeqAnalysisSampleQcStatus(seqQc, complete, complete.getText(), null);

        assertEquals("Passed", seqQc.getDataField("SeqQCStatus", null));
        assertEquals(Boolean.FALSE, seqQc.getDataField("PassedQc", null));
    }

    @Test
    public void setSeqAnalysisSampleQcStatusIGOComplete() throws Exception {
        DataRecord seqQc = new DataRecord(0, "QC", new MockDataRecord());
        QcStatus complete = QcStatus.IGO_COMPLETE;

        ToggleSampleQcStatus.setSeqAnalysisSampleQcStatus(seqQc, complete, complete.getText(), null);

        assertEquals("Passed", seqQc.getDataField("SeqQCStatus", null));
        assertEquals(Boolean.TRUE, seqQc.getDataField("PassedQc", null));
    }
}