package com.att.device.management.adapter;

import com.att.device.management.service.SimulatorClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default Protocol Adapter: Simplified TR-069 REST Connection-Request Nudge.
 */
@Component
public class RestNudgeProtocolAdapter implements DeviceProtocolAdapter {

    private final SimulatorClient simulatorClient;

    public RestNudgeProtocolAdapter(SimulatorClient simulatorClient) {
        this.simulatorClient = simulatorClient;
    }

    @Override
    public String getProtocolName() {
        return "REST_NUDGE";
    }

    @Override
    public boolean wakeUpOrSendCommand(String deviceId, String commandKey, String imageUrl, String targetVersion) {
        return simulatorClient.sendConnectionRequest(deviceId);
    }
}

