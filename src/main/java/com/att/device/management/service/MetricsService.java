package com.att.device.management.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

@Service
public class MetricsService {

    private final MeterRegistry registry;

    public MetricsService(MeterRegistry registry) {
        this.registry = registry;

        // Pre-register key monitored business and operational indicators
        for (String state : new String[]{"SUCCEEDED", "FAILED", "TIMED_OUT"}) {
            Counter.builder("firmware_upgrade_devices_total")
                    .tag("state", state)
                    .description("Total devices reaching terminal upgrade state")
                    .register(registry);
        }

        for (String status : new String[]{"RUNNING", "COMPLETED", "HALTED"}) {
            Counter.builder("firmware_upgrade_campaigns_total")
                    .tag("status", status)
                    .description("Total campaigns by status")
                    .register(registry);
        }
    }

    public void recordHttpRequest(String method, String endpoint, int statusCode) {
        Counter.builder("controller_http_requests_total")
                .tag("method", method)
                .tag("endpoint", endpoint)
                .tag("status", String.valueOf(statusCode))
                .description("Total HTTP requests received or processed")
                .register(registry)
                .increment();
    }

    public void recordStateTransition(String fromState, String toState) {
        Counter.builder("device_state_transitions_total")
                .tag("from", fromState)
                .tag("to", toState)
                .description("Count of state machine transitions")
                .register(registry)
                .increment();

        if ("SUCCEEDED".equalsIgnoreCase(toState) || "FAILED".equalsIgnoreCase(toState) || "TIMED_OUT".equalsIgnoreCase(toState)) {
            Counter.builder("firmware_upgrade_devices_total")
                    .tag("state", toState.toUpperCase())
                    .description("Total devices reaching terminal upgrade state")
                    .register(registry)
                    .increment();
        }
    }

    public void recordCampaignStatus(String status) {
        Counter.builder("firmware_upgrade_campaigns_total")
                .tag("status", status.toUpperCase())
                .description("Total campaigns by status")
                .register(registry)
                .increment();
    }

    public void recordNudgeSent(boolean success) {
        Counter.builder("device_nudges_total")
                .tag("result", success ? "DELIVERED" : "FAILED")
                .description("Total connection requests dispatched")
                .register(registry)
                .increment();
    }
}
