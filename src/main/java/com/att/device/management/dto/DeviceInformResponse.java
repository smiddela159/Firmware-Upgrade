package com.att.device.management.dto;

import java.util.List;

public record DeviceInformResponse(
        List<DeviceCommand> commands
) {}

