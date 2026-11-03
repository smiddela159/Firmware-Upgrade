package com.att.device.management.domain;

public enum DeviceState {
    PENDING,
    NOTIFIED,
    DOWNLOAD_SENT,
    ACCEPTED,
    REBOOTING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == TIMED_OUT;
    }

    public boolean isInFlight() {
        return this == NOTIFIED || this == DOWNLOAD_SENT || this == ACCEPTED || this == REBOOTING;
    }
}

