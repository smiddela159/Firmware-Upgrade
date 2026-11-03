package com.att.device.management.dto;

import java.time.Instant;

public record CreateCampaignResponse(
        String campaignId,
        String status,
        String targetVersion,
        long totalDevices,
        Instant createdAt,
        String createdBy
) {}

