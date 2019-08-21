package org.mskcc.limsrest.controller;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.mskcc.limsrest.ConnectionPoolLIMS;
import org.mskcc.limsrest.service.DeliveryEmail;
import org.mskcc.limsrest.service.GetDeliveryEmailDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.Future;

@RestController
@RequestMapping("/")
public class GetDeliveryEmail {
    private static Log log = LogFactory.getLog(GetDeliveryEmail.class);
    private final ConnectionPoolLIMS conn;
    private final GetDeliveryEmailDetails task;

    public GetDeliveryEmail(ConnectionPoolLIMS conn, GetDeliveryEmailDetails getQc) {
        this.conn = conn;
        this.task = getQc;
    }

    @GetMapping("/getDeliveryEmail")
    public DeliveryEmail getContent(@RequestParam(value="request", required=true) String request) {
        log.info("Starting get /getDeliveryEmail for request: " + request);

        if (request == null)
            return null;

        task.init(request);
        Future<Object> result = conn.submitTask(task);
        try {
            return (DeliveryEmail)result.get();
        } catch(Exception e) {
            return new DeliveryEmail(e.getMessage());
        }
    }
}