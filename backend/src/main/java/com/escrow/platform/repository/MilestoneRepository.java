package com.escrow.platform.repository;

import com.escrow.platform.model.MilestoneRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MilestoneRepository extends JpaRepository<MilestoneRecord, Long> {

    List<MilestoneRecord> findByOnChainDealIdOrderByMilestoneIndexAsc(Long onChainDealId);

    Optional<MilestoneRecord> findByOnChainDealIdAndMilestoneIndex(Long onChainDealId, int milestoneIndex);
}
