package com.att.device.management.dto;

import java.time.Instant;

public record StatusHistoryItemResponse(
        String fromStatus,
        String toStatus,
        String changedBy,
        Instant changedAt,
        String reason
) {}

