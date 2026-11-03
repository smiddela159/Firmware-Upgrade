package com.att.device.management.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FleetDeviceItem(
        String deviceId,
        String firmwareVersion
) {}

