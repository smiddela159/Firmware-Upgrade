package com.att.device.management.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record CampaignProgressResponse(
        String id,
        String status,
        String targetVersion,
        long totalDevices,
        long inFlight,
        long succeeded,
        long failed,
        long timedOut,
        double failureRatePercent,
        Map<String, Long> stateCounts,
        String haltReason,
        Instant createdAt,
        Instant completedAt,
        Long durationSeconds,
        String createdBy,
        String lastModifiedBy,
        Instant lastModifiedAt,
        List<StatusHistoryItemResponse> statusHistory,
        CampaignTimingMetricsResponse timingMetrics
) {}

