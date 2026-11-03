package com.att.device.management.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DeviceInformRequest(
        String deviceId,
        String firmwareVersion,
        String event,
        TransferCompleteInfo transferComplete
) {}

