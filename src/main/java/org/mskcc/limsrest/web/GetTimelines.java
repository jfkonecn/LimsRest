package org.mskcc.limsrest.web;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.mskcc.limsrest.connection.ConnectionPoolLIMS;
import org.mskcc.limsrest.limsapi.GetProjectHistory;
import org.mskcc.limsrest.limsapi.HistoricalEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedList;
import java.util.Set;
import java.util.concurrent.Future;

/**
 * Used on the alba IGO tools website, called about once a month.
 */
@RestController
@RequestMapping("/")
public class GetTimelines {
    private static Log log = LogFactory.getLog(GetTimelines.class);

    private final ConnectionPoolLIMS conn;
    private final GetProjectHistory task;

    public GetTimelines(ConnectionPoolLIMS conn, GetProjectHistory getHistory) {
        this.conn = conn;
        this.task = getHistory;
    }

    @GetMapping("/getTimeline")
    public LinkedList<HistoricalEvent> getContent(@RequestParam(value = "project") String[] project) {
        LinkedList<HistoricalEvent> timeline = new LinkedList<>();
        for (int i = 0; i < project.length; i++) {
            if (!Whitelists.requestMatches(project[i])) {
                return timeline;
            }
        }
        log.info("Starting get Timeline " + project[0]);
        task.init(project);
        Future<Object> result = conn.submitTask(task);
        try {
            timeline = new LinkedList((Set<HistoricalEvent>) result.get());
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        log.info("Completed timeline");
        return timeline;
    }
}