package com.att.device.management.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "campaign_status_history", indexes = {
    @Index(name = "idx_csh_campaign", columnList = "campaignId")
})
public class CampaignStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String campaignId;

    @Enumerated(EnumType.STRING)
    private CampaignStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CampaignStatus toStatus;

    @Column(nullable = false)
    private String changedBy;

    @Column(nullable = false)
    private Instant changedAt;

    private String reason;

    public CampaignStatusHistory() {
    }

    public CampaignStatusHistory(String campaignId, CampaignStatus fromStatus, CampaignStatus toStatus,
                                 String changedBy, Instant changedAt, String reason) {
        this.campaignId = campaignId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.changedBy = changedBy;
        this.changedAt = changedAt;
        this.reason = reason;
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

    public CampaignStatus getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(CampaignStatus fromStatus) {
        this.fromStatus = fromStatus;
    }

    public CampaignStatus getToStatus() {
        return toStatus;
    }

    public void setToStatus(CampaignStatus toStatus) {
        this.toStatus = toStatus;
    }

    public String getChangedBy() {
        return changedBy;
    }

    public void setChangedBy(String changedBy) {
        this.changedBy = changedBy;
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    public void setChangedAt(Instant changedAt) {
        this.changedAt = changedAt;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}

