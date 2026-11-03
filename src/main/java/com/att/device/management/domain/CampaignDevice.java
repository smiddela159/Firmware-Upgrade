package com.att.device.management.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "campaign_devices", indexes = {
    @Index(name = "idx_dev_campaign", columnList = "campaignId, state"),
    @Index(name = "idx_cmd_key", columnList = "commandKey")
})
public class CampaignDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String campaignId;

    @Column(nullable = false)
    private String deviceId;

    @Column(nullable = false)
    private String initialVersion;

    private String currentVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeviceState state;

    private String commandKey;

    private int nudgeRetries;

    private Instant lastStateChange;

    private Instant notifiedAt;

    private Instant downloadSentAt;

    private Instant acceptedAt;

    private Instant rebootingAt;

    private Instant terminalAt;

    private String failureReason;

    public CampaignDevice() {
    }

    public CampaignDevice(String campaignId, String deviceId, String initialVersion) {
        this.campaignId = campaignId;
        this.deviceId = deviceId;
        this.initialVersion = initialVersion;
        this.currentVersion = initialVersion;
        this.state = DeviceState.PENDING;
        this.nudgeRetries = 0;
        this.lastStateChange = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getCampaignId() {
        return campaignId;
    }

    public void setCampaignId(String campaignId) {
        this.campaignId = campaignId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getInitialVersion() {
        return initialVersion;
    }

    public void setInitialVersion(String initialVersion) {
        this.initialVersion = initialVersion;
    }

    public String getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(String currentVersion) {
        this.currentVersion = currentVersion;
    }

    public DeviceState getState() {
        return state;
    }

    public void setState(DeviceState state) {
        this.state = state;
        this.lastStateChange = Instant.now();
    }

    public String getCommandKey() {
        return commandKey;
    }

    public void setCommandKey(String commandKey) {
        this.commandKey = commandKey;
    }

    public int getNudgeRetries() {
        return nudgeRetries;
    }

    public void setNudgeRetries(int nudgeRetries) {
        this.nudgeRetries = nudgeRetries;
    }

    public void incrementNudgeRetries() {
        this.nudgeRetries++;
    }

    public Instant getLastStateChange() {
        return lastStateChange;
    }

    public void setLastStateChange(Instant lastStateChange) {
        this.lastStateChange = lastStateChange;
    }

    public Instant getNotifiedAt() {
        return notifiedAt;
    }

    public void setNotifiedAt(Instant notifiedAt) {
        this.notifiedAt = notifiedAt;
    }

    public Instant getDownloadSentAt() {
        return downloadSentAt;
    }

    public void setDownloadSentAt(Instant downloadSentAt) {
        this.downloadSentAt = downloadSentAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public void setAcceptedAt(Instant acceptedAt) {
        this.acceptedAt = acceptedAt;
    }

    public Instant getRebootingAt() {
        return rebootingAt;
    }

    public void setRebootingAt(Instant rebootingAt) {
        this.rebootingAt = rebootingAt;
    }

    public Instant getTerminalAt() {
        return terminalAt;
    }

    public void setTerminalAt(Instant terminalAt) {
        this.terminalAt = terminalAt;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }
}

