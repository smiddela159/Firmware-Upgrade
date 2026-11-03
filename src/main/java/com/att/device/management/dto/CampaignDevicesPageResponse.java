package com.att.device.management.dto;

import java.util.List;

public record CampaignDevicesPageResponse(
        String campaignId,
        long totalElements,
        int totalPages,
        int page,
        int size,
        List<CampaignDeviceResponse> devices
) {}

