package org.mskcc.limsrest.web;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.mskcc.limsrest.connection.ConnectionQueue;
import org.mskcc.limsrest.limsapi.GetSampleQc;
import org.mskcc.limsrest.limsapi.RequestSummary;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Future;

@RestController
public class GetProjectQc {

    private Log log = LogFactory.getLog(GetProjectQc.class);
    private final ConnectionQueue connQueue;
    private final GetSampleQc task;

    public GetProjectQc( ConnectionQueue connQueue, GetSampleQc getQc){
        this.connQueue = connQueue;
        this.task = getQc;
    }

    @RequestMapping("/getProjectQc")
    public List<RequestSummary> getContent(@RequestParam(value="project", required=false) String[] project) {
        log.info("Starting get /getProjectQc for projects: " + Arrays.toString(project));

        if (project == null)
            return new LinkedList<>();

        for (int i = 0; i < project.length; i++) {
            if (!Whitelists.requestMatches(project[i])) {
                log.info("FAILURE: project is not using a valid format");
                return new LinkedList<>();
            }
        }

        task.init(project);
        Future<Object> result = connQueue.submitTask(task);
        List<RequestSummary> rss = new LinkedList<>();
        try {
            rss = (List<RequestSummary>)result.get();
        } catch(Exception e) {
            RequestSummary rs = new RequestSummary();
            rs.setInvestigator(e.getMessage());
            rss.add(rs);
        }
        return rss;
    }
}