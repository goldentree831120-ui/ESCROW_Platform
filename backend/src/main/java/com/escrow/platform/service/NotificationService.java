package com.escrow.platform.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Notification stub. In a real deployment this is where you'd call an email
 * provider (SES/SendGrid), push a webhook, or fire a WebSocket event to a
 * connected frontend. Kept as an interface-shaped single class with @Async
 * methods so wiring in a real provider later doesn't touch the event
 * listener that calls it.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    @Async
    public void notifyMilestoneApproved(long dealId, int milestoneIndex, String freelancerAddress) {
        log.info("[notify] would email {} - milestone {} on deal {} was approved and paid",
                freelancerAddress, milestoneIndex, dealId);
    }

    @Async
    public void notifyDisputeRaised(long dealId, int milestoneIndex, String raisedBy) {
        log.info("[notify] would email the arbiter - milestone {} on deal {} was disputed by {}",
                milestoneIndex, dealId, raisedBy);
    }

    @Async
    public void notifyDisputeResolved(long dealId, int milestoneIndex, boolean releasedToFreelancer) {
        log.info("[notify] would email both parties - dispute on milestone {} of deal {} resolved ({})",
                milestoneIndex, dealId, releasedToFreelancer ? "paid to freelancer" : "refunded to client");
    }

    @Async
    public void notifyDealCompleted(long dealId) {
        log.info("[notify] would email both parties - deal {} is fully completed", dealId);
    }
}
