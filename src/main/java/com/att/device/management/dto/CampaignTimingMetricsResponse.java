package com.att.device.management.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CampaignTimingMetricsResponse(
        Long activeRunningSeconds,
        Long pausedSeconds,
        Long totalElapsedSeconds,
        Double averageRebootSeconds,
        Double averageTotalDeviceSeconds
) {}

