package com.escrow.platform.dto;

import com.escrow.platform.model.DealRecord;
import com.escrow.platform.model.MilestoneRecord;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

public class DealDtos {

    private static final BigInteger WEI_PER_ETHER = BigInteger.TEN.pow(18);

    private static BigDecimal toEth(String wei) {
        return new BigDecimal(new BigInteger(wei)).divide(new BigDecimal(WEI_PER_ETHER));
    }

    public record MilestoneView(int index, BigDecimal amountEth, String status, String description) {
        public static MilestoneView from(MilestoneRecord m) {
            return new MilestoneView(m.getMilestoneIndex(), toEth(m.getAmountWei()), m.getStatus().name(), m.getDescription());
        }
    }

    public record DealView(
            Long dealId,
            String title,
            String description,
            String clientAddress,
            String freelancerAddress,
            String arbiterAddress,
            BigDecimal totalAmountEth,
            BigDecimal releasedAmountEth,
            String status,
            Instant createdAt,
            Instant lastSyncedAt,
            List<MilestoneView> milestones
    ) {
        public static DealView from(DealRecord deal, List<MilestoneRecord> milestones) {
            return new DealView(
                    deal.getOnChainDealId(),
                    deal.getTitle(),
                    deal.getDescription(),
                    deal.getClientAddress(),
                    deal.getFreelancerAddress(),
                    deal.getArbiterAddress(),
                    toEth(deal.getTotalAmountWei()),
                    toEth(deal.getReleasedAmountWei()),
                    deal.getStatus().name(),
                    deal.getCreatedAt(),
                    deal.getLastSyncedAt(),
                    milestones.stream().map(MilestoneView::from).toList()
            );
        }
    }

    public record UpdateDealMetadataRequest(@Size(max = 200) String title, @Size(max = 2000) String description) {
    }

    public record UpdateMilestoneMetadataRequest(@Size(max = 500) String description) {
    }
}
