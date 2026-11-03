package com.att.device.management.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeviceDurationsResponse(
        Long nudgeDurationSeconds,
        Long downloadDurationSeconds,
        Long rebootDurationSeconds,
        Long totalDurationSeconds
) {}

