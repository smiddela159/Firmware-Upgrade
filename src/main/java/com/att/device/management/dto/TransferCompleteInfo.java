package com.att.device.management.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TransferCompleteInfo(
        String commandKey,
        int faultCode,
        Long startTime,
        Long completeTime
) {}

