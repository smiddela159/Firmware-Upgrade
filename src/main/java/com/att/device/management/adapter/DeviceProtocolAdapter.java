package com.att.device.management.adapter;

import java.util.concurrent.CompletableFuture;

/**
 * Pluggable Protocol Adapter SPI (Service Provider Interface).
 * Decouples the core campaign state machine from specific device transport protocols:
 * - REST Nudge / HTTP Inform (TR-069 CWMP)
 * - Persistent Push via MQTT (TR-369 USP)
 * - Persistent Push via WebSockets / STOMP
 * - Bidirectional gRPC Streaming
 * - CoAP / LwM2M for IoT / cellular gateways
 */
public interface DeviceProtocolAdapter {

    /**
     * Unique protocol identifier, e.g. "REST_NUDGE", "USP_MQTT", "USP_WEBSOCKET", "GRPC".
     */
    String getProtocolName();

    /**
     * Wakes up or delivers download instruction to the device.
     * Returns true if successfully delivered or acknowledged.
     */
    boolean wakeUpOrSendCommand(String deviceId, String commandKey, String imageUrl, String targetVersion);

    /**
     * Optional async command dispatch for high-throughput push protocols.
     */
    default CompletableFuture<Boolean> sendCommandAsync(String deviceId, String commandKey, String imageUrl, String targetVersion) {
        return CompletableFuture.completedFuture(wakeUpOrSendCommand(deviceId, commandKey, imageUrl, targetVersion));
    }
}

