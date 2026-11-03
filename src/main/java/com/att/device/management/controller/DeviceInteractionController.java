package com.att.device.management.controller;

import com.att.device.management.dto.DeviceCommandResponseRequest;
import com.att.device.management.dto.DeviceInformRequest;
import com.att.device.management.dto.DeviceInformResponse;
import com.att.device.management.service.DeviceSessionService;
import com.att.device.management.service.MetricsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/devices")
public class DeviceInteractionController {

    private final DeviceSessionService sessionService;
    private final MetricsService metricsService;

    public DeviceInteractionController(DeviceSessionService sessionService, MetricsService metricsService) {
        this.sessionService = sessionService;
        this.metricsService = metricsService;
    }

    /**
     * POST /devices/{deviceId}/inform
     * Device opens session: returns pending commands.
     */
    @PostMapping("/{deviceId}/inform")
    public ResponseEntity<DeviceInformResponse> handleInform(
            @PathVariable("deviceId") String deviceId,
            @RequestBody DeviceInformRequest request) {
        DeviceInformResponse response = sessionService.handleInform(deviceId, request);
        metricsService.recordHttpRequest("POST", "/devices/{deviceId}/inform", 200);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /devices/{deviceId}/command-response
     * Device acknowledges command.
     */
    @PostMapping("/{deviceId}/command-response")
    public ResponseEntity<Void> handleCommandResponse(
            @PathVariable("deviceId") String deviceId,
            @RequestBody DeviceCommandResponseRequest request) {
        sessionService.handleCommandResponse(deviceId, request);
        metricsService.recordHttpRequest("POST", "/devices/{deviceId}/command-response", 200);
        return ResponseEntity.ok().build();
    }
}

