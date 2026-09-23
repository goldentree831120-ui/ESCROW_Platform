package com.escrow.platform.model;

import jakarta.persistence.*;

@Entity
@Table(name = "milestone_record", uniqueConstraints = @UniqueConstraint(columnNames = {"onChainDealId", "milestoneIndex"}))
public class MilestoneRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long onChainDealId;

    @Column(nullable = false)
    private int milestoneIndex;

    @Column(nullable = false, length = 78)
    private String amountWei;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MilestoneStatus status = MilestoneStatus.PENDING;

    protected MilestoneRecord() {
        // JPA
    }

    public MilestoneRecord(Long onChainDealId, int milestoneIndex, String amountWei) {
        this.onChainDealId = onChainDealId;
        this.milestoneIndex = milestoneIndex;
        this.amountWei = amountWei;
    }

    public enum MilestoneStatus {
        PENDING, RELEASED, DISPUTED
    }

    public Long getId() {
        return id;
    }

    public Long getOnChainDealId() {
        return onChainDealId;
    }

    public int getMilestoneIndex() {
        return milestoneIndex;
    }

    public String getAmountWei() {
        return amountWei;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public MilestoneStatus getStatus() {
        return status;
    }

    public void setStatus(MilestoneStatus status) {
        this.status = status;
    }
}
