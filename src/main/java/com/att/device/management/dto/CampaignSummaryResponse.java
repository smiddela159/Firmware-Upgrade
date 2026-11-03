package com.att.device.management.dto;

import java.time.Instant;

public record CampaignSummaryResponse(
        String id,
        String status,
        String targetVersion,
        long totalDevices,
        long succeeded,
        long failed,
        long timedOut,
        double failureRatePercent,
        Instant createdAt,
        Instant completedAt,
        String createdBy,
        String lastModifiedBy,
        Instant lastModifiedAt
) {}

