package com.att.device.management.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "campaigns")
public class Campaign {

    @Id
    private String id;

    @Column(nullable = false)
    private String targetVersion;

    @Column(nullable = false)
    private String imageUrl;

    @Column(nullable = false)
    private String currentVersionFilter;

    @Column(nullable = false)
    private int maxConcurrent;

    @Column(nullable = false)
    private double failureThresholdPercent;

    @Column(nullable = true)
    private String ownerId;

    private String createdBy;

    private String lastModifiedBy;

    private Instant lastModifiedAt;

    @Column(nullable = false)
    private CampaignStatus status;

    private String haltReason;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant completedAt;

    public Campaign() {
    }

    public Campaign(String id, String targetVersion, String imageUrl, String currentVersionFilter,
                    int maxConcurrent, double failureThresholdPercent) {
        this.id = id;
        this.targetVersion = targetVersion;
        this.imageUrl = imageUrl;
        this.currentVersionFilter = currentVersionFilter;
        this.maxConcurrent = maxConcurrent;
        this.failureThresholdPercent = failureThresholdPercent;
        this.status = CampaignStatus.RUNNING;
        this.createdAt = Instant.now();
        this.lastModifiedAt = this.createdAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTargetVersion() {
        return targetVersion;
    }

    public void setTargetVersion(String targetVersion) {
        this.targetVersion = targetVersion;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public String getCurrentVersionFilter() {
        return currentVersionFilter;
    }

    public void setCurrentVersionFilter(String currentVersionFilter) {
        this.currentVersionFilter = currentVersionFilter;
    }

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    public void setMaxConcurrent(int maxConcurrent) {
        this.maxConcurrent = maxConcurrent;
    }

    public double getFailureThresholdPercent() {
        return failureThresholdPercent;
    }

    public void setFailureThresholdPercent(double failureThresholdPercent) {
        this.failureThresholdPercent = failureThresholdPercent;
    }

    public CampaignStatus getStatus() {
        return status;
    }

    public void setStatus(CampaignStatus status) {
        this.status = status;
    }

    public String getHaltReason() {
        return haltReason;
    }

    public void setHaltReason(String haltReason) {
        this.haltReason = haltReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getLastModifiedBy() {
        return lastModifiedBy;
    }

    public void setLastModifiedBy(String lastModifiedBy) {
        this.lastModifiedBy = lastModifiedBy;
    }

    public Instant getLastModifiedAt() {
        return lastModifiedAt;
    }

    public void setLastModifiedAt(Instant lastModifiedAt) {
        this.lastModifiedAt = lastModifiedAt;
    }
}

