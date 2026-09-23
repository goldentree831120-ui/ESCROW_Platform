package com.escrow.platform.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * The off-chain record of a deal, keyed by its on-chain id. The chain
 * remains the source of truth for money movement (who's been paid, how
 * much); this table adds the business metadata a contract has no reason to
 * store (a title, a description, timestamps of when the backend last saw
 * each state change) and lets the API answer "list my deals" without
 * scanning chain history on every request.
 */
@Entity
@Table(name = "deal_record")
public class DealRecord {

    @Id
    private Long onChainDealId;

    @Column(nullable = false, length = 42)
    private String clientAddress;

    @Column(nullable = false, length = 42)
    private String freelancerAddress;

    @Column(nullable = false, length = 42)
    private String arbiterAddress;

    private String title;

    @Column(length = 2000)
    private String description;

    @Column(nullable = false, length = 78)
    private String totalAmountWei;

    @Column(nullable = false, length = 78)
    private String releasedAmountWei = "0";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DealStatus status = DealStatus.ACTIVE;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant lastSyncedAt;

    protected DealRecord() {
        // JPA
    }

    public DealRecord(Long onChainDealId, String clientAddress, String freelancerAddress,
                       String arbiterAddress, String totalAmountWei, Instant createdAt) {
        this.onChainDealId = onChainDealId;
        this.clientAddress = clientAddress.toLowerCase();
        this.freelancerAddress = freelancerAddress.toLowerCase();
        this.arbiterAddress = arbiterAddress.toLowerCase();
        this.totalAmountWei = totalAmountWei;
        this.createdAt = createdAt;
        this.lastSyncedAt = Instant.now();
    }

    public enum DealStatus {
        ACTIVE, COMPLETED, CANCELLED
    }

    public Long getOnChainDealId() {
        return onChainDealId;
    }

    public String getClientAddress() {
        return clientAddress;
    }

    public String getFreelancerAddress() {
        return freelancerAddress;
    }

    public String getArbiterAddress() {
        return arbiterAddress;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getTotalAmountWei() {
        return totalAmountWei;
    }

    public String getReleasedAmountWei() {
        return releasedAmountWei;
    }

    public void setReleasedAmountWei(String releasedAmountWei) {
        this.releasedAmountWei = releasedAmountWei;
    }

    public DealStatus getStatus() {
        return status;
    }

    public void setStatus(DealStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }

    public void touchSync() {
        this.lastSyncedAt = Instant.now();
    }
}
