package com.att.device.management.service;

import com.att.device.management.dto.FleetDeviceItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;

@Service
public class SimulatorClient {

    private static final Logger log = LoggerFactory.getLogger(SimulatorClient.class);

    private final RestClient restClient;
    private final MetricsService metricsService;

    public SimulatorClient(@Value("${controller.simulator-url}") String simulatorUrl,
                           MetricsService metricsService) {
        this.restClient = RestClient.builder()
                .baseUrl(simulatorUrl)
                .build();
        this.metricsService = metricsService;
    }

    /**
     * Lists eligible fleet devices: GET {simulatorUrl}/devices?version={firmwareVersion}
     */
    public List<FleetDeviceItem> discoverFleet(String targetCurrentVersion) {
        try {
            ResponseEntity<List<FleetDeviceItem>> resp = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/devices")
                            .queryParam("version", targetCurrentVersion)
                            .build())
                    .retrieve()
                    .toEntity(new ParameterizedTypeReference<List<FleetDeviceItem>>() {});

            int code = resp.getStatusCode().value();
            metricsService.recordHttpRequest("GET", "/devices", code);
            return resp.getBody() != null ? resp.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to query fleet from simulator: {}", e.getMessage());
            metricsService.recordHttpRequest("GET", "/devices", 500);
            return Collections.emptyList();
        }
    }

    /**
     * Dispatches wake-up nudge: POST {simulatorUrl}/devices/{deviceId}/connection-request
     * Returns true if 202 Accepted.
     */
    public boolean sendConnectionRequest(String deviceId) {
        try {
            ResponseEntity<Void> resp = restClient.post()
                    .uri("/devices/{deviceId}/connection-request", deviceId)
                    .retrieve()
                    .toBodilessEntity();

            int code = resp.getStatusCode().value();
            metricsService.recordHttpRequest("POST", "/devices/{deviceId}/connection-request", code);
            boolean success = (code == 202 || code == 200);
            metricsService.recordNudgeSent(success);
            return success;
        } catch (Exception e) {
            log.debug("Nudge failed for device {}: {}", deviceId, e.getMessage());
            metricsService.recordHttpRequest("POST", "/devices/{deviceId}/connection-request", 504);
            metricsService.recordNudgeSent(false);
            return false;
        }
    }
}

