package com.escrow.platform.controller;

import com.escrow.platform.dto.DealDtos.DealView;
import com.escrow.platform.dto.DealDtos.UpdateDealMetadataRequest;
import com.escrow.platform.dto.DealDtos.UpdateMilestoneMetadataRequest;
import com.escrow.platform.model.AppUser;
import com.escrow.platform.model.DealRecord;
import com.escrow.platform.model.MilestoneRecord;
import com.escrow.platform.repository.DealRepository;
import com.escrow.platform.repository.MilestoneRepository;
import com.escrow.platform.repository.UserRepository;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Off-chain metadata lives here, keyed by the on-chain deal id that
 * {@link com.escrow.platform.service.EscrowEventListenerService} writes.
 * The chain (via the frontend + MetaMask) is what actually moves funds -
 * this API never signs transactions on a user's behalf, which is the
 * correct trust boundary for a custodial-free escrow app.
 */
@RestController
@RequestMapping("/api/deals")
public class DealController {

    private final DealRepository dealRepository;
    private final MilestoneRepository milestoneRepository;
    private final UserRepository userRepository;

    public DealController(DealRepository dealRepository, MilestoneRepository milestoneRepository,
                           UserRepository userRepository) {
        this.dealRepository = dealRepository;
        this.milestoneRepository = milestoneRepository;
        this.userRepository = userRepository;
    }

    /** Deals where the current user's linked wallet is either the client or the freelancer. */
    @GetMapping("/mine")
    public ResponseEntity<List<DealView>> myDeals(Authentication auth) {
        AppUser user = currentUser(auth);
        if (user.getWalletAddress() == null) {
            return ResponseEntity.badRequest().build();
        }

        List<DealRecord> deals = dealRepository.findByClientAddressIgnoreCaseOrFreelancerAddressIgnoreCase(
                user.getWalletAddress(), user.getWalletAddress());

        List<DealView> views = deals.stream()
                .map(d -> DealView.from(d, milestoneRepository.findByOnChainDealIdOrderByMilestoneIndexAsc(d.getOnChainDealId())))
                .toList();

        return ResponseEntity.ok(views);
    }

    @GetMapping("/{dealId}")
    public ResponseEntity<DealView> getDeal(@PathVariable Long dealId) {
        return dealRepository.findById(dealId)
                .map(d -> ResponseEntity.ok(DealView.from(d, milestoneRepository.findByOnChainDealIdOrderByMilestoneIndexAsc(dealId))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Attaches a human-readable title/description to a deal that already
     * exists on-chain (created by the event listener). Only the client or
     * freelancer on that deal may edit it.
     */
    @PatchMapping("/{dealId}")
    public ResponseEntity<?> updateDealMetadata(@PathVariable Long dealId, @Valid @RequestBody UpdateDealMetadataRequest request,
                                                 Authentication auth) {
        DealRecord deal = dealRepository.findById(dealId).orElse(null);
        if (deal == null) return ResponseEntity.notFound().build();

        AppUser user = currentUser(auth);
        if (!isPartyToDeal(user, deal)) {
            return ResponseEntity.status(403).body("You are not a party to this deal");
        }

        if (request.title() != null) deal.setTitle(request.title());
        if (request.description() != null) deal.setDescription(request.description());
        dealRepository.save(deal);

        return ResponseEntity.ok(DealView.from(deal, milestoneRepository.findByOnChainDealIdOrderByMilestoneIndexAsc(dealId)));
    }

    @PatchMapping("/{dealId}/milestones/{index}")
    public ResponseEntity<?> updateMilestoneMetadata(@PathVariable Long dealId, @PathVariable int index,
                                                       @Valid @RequestBody UpdateMilestoneMetadataRequest request,
                                                       Authentication auth) {
        DealRecord deal = dealRepository.findById(dealId).orElse(null);
        if (deal == null) return ResponseEntity.notFound().build();

        AppUser user = currentUser(auth);
        if (!isPartyToDeal(user, deal)) {
            return ResponseEntity.status(403).body("You are not a party to this deal");
        }

        MilestoneRecord milestone = milestoneRepository.findByOnChainDealIdAndMilestoneIndex(dealId, index).orElse(null);
        if (milestone == null) return ResponseEntity.notFound().build();

        milestone.setDescription(request.description());
        milestoneRepository.save(milestone);

        return ResponseEntity.ok(com.escrow.platform.dto.DealDtos.MilestoneView.from(milestone));
    }

    private boolean isPartyToDeal(AppUser user, DealRecord deal) {
        String wallet = user.getWalletAddress();
        return wallet != null && (wallet.equalsIgnoreCase(deal.getClientAddress())
                || wallet.equalsIgnoreCase(deal.getFreelancerAddress()));
    }

    private AppUser currentUser(Authentication auth) {
        String email = ((User) auth.getPrincipal()).getUsername();
        return userRepository.findByEmailIgnoreCase(email).orElseThrow();
    }
}
