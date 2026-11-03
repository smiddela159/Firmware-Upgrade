package com.att.device.management.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record CreateCampaignRequest(
        @NotBlank(message = "targetVersion is required")
        String targetVersion,

        @NotBlank(message = "imageUrl is required")
        String imageUrl,

        Map<String, String> deviceFilter,
        int maxConcurrent,
        double failureThresholdPercent
) {}

