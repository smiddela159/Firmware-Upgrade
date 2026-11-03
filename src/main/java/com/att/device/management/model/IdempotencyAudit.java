package com.att.device.management.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Persistent audit record for idempotent requests. Used to retain information about
 * successful, validation‑failed, and transient‑failed executions even after the
 * in‑memory cache entry has expired.
 */
@Entity
@Table(name = "idempotency_audit", indexes = {@Index(columnList = "idempotency_key")})
public class IdempotencyAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 128)
    private String key;

    /**
     * One of: PROCESSING, SUCCESS, FAILED_VALIDATION, FAILED_TRANSIENT
     */
    @Column(nullable = false, length = 32)
    private String status;

    /** SHA‑256 hash (Base64 URL) of the request body – helps detecting payload changes. */
    @Column(name = "payload_hash", length = 64)
    private String payloadHash;

    @Lob
    @Column(name = "response_json")
    private String responseJson;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    // ---------------------------------------------------------------------
    // Getters & Setters (generated)
    // ---------------------------------------------------------------------
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }

    public String getResponseJson() { return responseJson; }
    public void setResponseJson(String responseJson) { this.responseJson = responseJson; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

