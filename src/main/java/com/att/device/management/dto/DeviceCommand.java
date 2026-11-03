package com.att.device.management.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeviceCommand(
        String type,
        String commandKey,
        String url,
        String targetVersion
) {}

