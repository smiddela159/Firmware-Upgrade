package com.att.device.management.dto;

import java.time.Instant;

public record CampaignDeviceResponse(
        String deviceId,
        String state,
        String initialVersion,
        String currentVersion,
        int nudgeRetries,
        String failureReason,
        Instant notifiedAt,
        Instant downloadSentAt,
        Instant acceptedAt,
        Instant rebootingAt,
        Instant terminalAt,
        DeviceDurationsResponse durations
) {}

