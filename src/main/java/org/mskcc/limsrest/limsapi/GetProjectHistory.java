
package org.mskcc.limsrest.limsapi;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.rmi.RemoteException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.LinkedList;
import java.util.Map;
import java.util.HashMap;
import java.util.TreeSet;
import java.util.concurrent.*;

import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.velox.api.datamgmtserver.DataMgmtServer;
import com.velox.api.datarecord.*;
import com.velox.api.user.User;
import com.velox.api.util.ServerException;
import com.velox.sapioutils.client.standalone.VeloxConnection;
import com.velox.sapioutils.client.standalone.VeloxStandalone;
import com.velox.sapioutils.client.standalone.VeloxStandaloneException;
import com.velox.sapioutils.client.standalone.VeloxStandaloneManagerContext;
import com.velox.sapioutils.client.standalone.VeloxExecutable;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;


/**
 * A queued task that takes a request id and returns all the start and end date for task based on the creation and last modified date of every sample 
 * 
 * @author Aaron Gabow
 * 
 */
@Service
public class GetProjectHistory  extends LimsTask 
{
  
  private Log log = LogFactory.getLog(GetProjectHistory.class);

  protected String[] projects;

  public void init(String[] projects){
    if(projects != null)
        this.projects = projects.clone();
  }

 //execute the velox call
@PreAuthorize("hasRole('READ')")
@Override
 public Object execute(VeloxConnection conn){
// private void runProgram(User apiUser, DataRecordManager dataRecordManager) {
  TreeSet<HistoricalEvent> known = new TreeSet<>();
  
  log.info("Accessing lims");  

  try {
    if(projects == null){
      throw new Exception("Unable to get project information with no project specified");
    }
    StringBuffer sb = new StringBuffer();
    for(int i = 0; i < projects.length - 1; i++){
      sb.append("'");
      sb.append(projects[i]);
      sb.append("',");
    }
    sb.append("'");
    sb.append(projects[projects.length - 1]);
    sb.append("'");
    List<DataRecord> requestList = dataRecordManager.queryDataRecords("Request", "RequestId in (" + sb.toString()  +")", user);
    
    for(DataRecord r: requestList){
      long requestStart = r.getLongVal("DateCreated", user);
      long requestComplete = 0;
      try{ requestComplete  =  r.getLongVal("CompletedDate", user);} catch(NullPointerException npe){}
      String project = "";
      try{ project  =  r.getStringVal("RequestId", user);} catch(NullPointerException npe){}

      known.add(new HistoricalEvent(requestStart, requestComplete,  project, "Initiated"));

      List<DataRecord> samples = r.getDescendantsOfType("Sample", user);
      List<DataRecord> plates = r.getDescendantsOfType("Plate", user);
      samples.addAll(plates);
         for(DataRecord sample : samples){
            log.info("processing");
            long start = sample.getLongVal("DateCreated", user); 
            long end  = 0;
            try{
               end = sample.getLongVal("DateModified", user);
            }
            catch(NotFound nf){
               end = start; //plates do not have date modified
            }
            catch(NullPointerException npe){
                log.info("Null end for sample");
            }
            String status = null;
            try {
               status = sample.getStringVal("ExemplarSampleStatus", user);
            } 
            catch (NotFound nf){
                try{
                  status = sample.getStringVal("ExemplarPlateStatus", user);
                } catch (Exception e){}
            } 
            catch (NullPointerException npe){}
            if(status == null){
                log.info("Skipping a null status sample");
                continue;
            }
            String requestId = project;
            try{ requestId  =  sample.getStringVal("RequestId", user);} catch(NullPointerException | NotFound e){}
            String[] statusElements = status.split(" - ");
            if(statusElements[0].equals("Received")){
               end = start;
            }
            else if(!statusElements[0].equals("Completed")){
                log.info("Status not completed:" + status);
                end = 0;
            }
            log.info("End: " + end);
            if(statusElements.length > 1){
              HistoricalEvent event = new HistoricalEvent(start, end, requestId, statusElements[1]);
              known.add(event);
            }  else{
              HistoricalEvent event = new HistoricalEvent(start, end, requestId, status);
              known.add(event);
            }

         }
    }
  } catch (Throwable e) {
      StringWriter sw = new StringWriter();
      PrintWriter pw = new PrintWriter(sw);
      e.printStackTrace(pw);
    log.info(e.getMessage() + " TRACE: " + sw.toString());

  }

  return known;
 }

}