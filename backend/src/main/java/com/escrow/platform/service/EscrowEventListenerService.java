package com.escrow.platform.service;

import com.escrow.platform.config.Web3Properties;
import com.escrow.platform.model.DealRecord;
import com.escrow.platform.model.MilestoneRecord;
import com.escrow.platform.repository.DealRepository;
import com.escrow.platform.repository.MilestoneRepository;
import io.reactivex.disposables.Disposable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.EventValues;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.tx.Contract;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Optional;

/**
 * Subscribes to MilestoneEscrow's event logs via Web3j and keeps the
 * Postgres record of deals/milestones in sync with the chain. This is the
 * piece that answers "how do you reconcile on-chain state with an off-chain
 * system" - probably the single most common backend-integration question
 * for a Java engineer moving into Web3.
 *
 * Events are decoded by hand (defining {@link Event} objects and using
 * {@link Contract#staticExtractEventParameters}) rather than through a
 * generated contract wrapper, for the same reason as {@link ChainReaderService}:
 * it avoids an extra `web3j generate solidity` build step and shows what
 * that generated code does under the hood.
 */
@Service
public class EscrowEventListenerService {

    private static final Logger log = LoggerFactory.getLogger(EscrowEventListenerService.class);

    private static final Event DEAL_CREATED = new Event("DealCreated", java.util.List.of(
            new TypeReference<Uint256>(true) {},   // dealId (indexed)
            new TypeReference<Address>(true) {},   // client (indexed)
            new TypeReference<Address>(true) {},   // freelancer (indexed)
            new TypeReference<Address>(false) {},  // arbiter
            new TypeReference<Uint256>(false) {},  // totalAmount
            new TypeReference<Uint256>(false) {}   // milestoneCount
    ));

    private static final Event MILESTONE_APPROVED = new Event("MilestoneApproved", java.util.List.of(
            new TypeReference<Uint256>(true) {},
            new TypeReference<Uint256>(true) {},
            new TypeReference<Uint256>(false) {}
    ));

    private static final Event DISPUTE_RAISED = new Event("DisputeRaised", java.util.List.of(
            new TypeReference<Uint256>(true) {},
            new TypeReference<Uint256>(true) {},
            new TypeReference<Address>(false) {}
    ));

    private static final Event DISPUTE_RESOLVED = new Event("DisputeResolved", java.util.List.of(
            new TypeReference<Uint256>(true) {},
            new TypeReference<Uint256>(true) {},
            new TypeReference<Bool>(false) {}
    ));

    private static final Event DEAL_CANCELLED = new Event("DealCancelled", java.util.List.of(
            new TypeReference<Uint256>(true) {},
            new TypeReference<Uint256>(false) {}
    ));

    private static final Event DEAL_COMPLETED = new Event("DealCompleted", java.util.List.of(
            new TypeReference<Uint256>(true) {}
    ));

    private final Web3j web3j;
    private final Web3Properties props;
    private final ChainReaderService chainReader;
    private final DealRepository dealRepository;
    private final MilestoneRepository milestoneRepository;
    private final NotificationService notificationService;

    private Disposable subscription;

    public EscrowEventListenerService(Web3j web3j, Web3Properties props, ChainReaderService chainReader,
                                       DealRepository dealRepository, MilestoneRepository milestoneRepository,
                                       NotificationService notificationService) {
        this.web3j = web3j;
        this.props = props;
        this.chainReader = chainReader;
        this.dealRepository = dealRepository;
        this.milestoneRepository = milestoneRepository;
        this.notificationService = notificationService;
    }

    @PostConstruct
    public void start() {
        if (props.getEscrowAddress() == null || props.getEscrowAddress().startsWith("0x0000000000")) {
            log.warn("web3.escrow-address is not configured - event listener will not start. " +
                    "Deploy the contract and set ESCROW_ADDRESS to enable it.");
            return;
        }

        EthFilter filter = new EthFilter(
                DefaultBlockParameter.valueOf(BigInteger.valueOf(props.getStartBlock())),
                DefaultBlockParameterName.LATEST,
                props.getEscrowAddress()
        );

        subscription = web3j.ethLogFlowable(filter).subscribe(this::handleLog, throwable ->
                log.error("Escrow event subscription failed", throwable));

        log.info("Subscribed to MilestoneEscrow events at {} from block {}",
                props.getEscrowAddress(), props.getStartBlock());
    }

    @PreDestroy
    public void stop() {
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
    }

    private void handleLog(Log eventLog) {
        String topic0 = eventLog.getTopics().isEmpty() ? "" : eventLog.getTopics().get(0);

        try {
            if (topic0.equals(EventEncoder.encode(DEAL_CREATED))) {
                onDealCreated(eventLog);
            } else if (topic0.equals(EventEncoder.encode(MILESTONE_APPROVED))) {
                onMilestoneApproved(eventLog);
            } else if (topic0.equals(EventEncoder.encode(DISPUTE_RAISED))) {
                onDisputeRaised(eventLog);
            } else if (topic0.equals(EventEncoder.encode(DISPUTE_RESOLVED))) {
                onDisputeResolved(eventLog);
            } else if (topic0.equals(EventEncoder.encode(DEAL_CANCELLED))) {
                onDealCancelled(eventLog);
            } else if (topic0.equals(EventEncoder.encode(DEAL_COMPLETED))) {
                onDealCompleted(eventLog);
            }
        } catch (Exception e) {
            log.error("Failed to process event log at tx {}", eventLog.getTransactionHash(), e);
        }
    }

    private void onDealCreated(Log eventLog) {
        EventValues values = Contract.staticExtractEventParameters(DEAL_CREATED, eventLog);

        long dealId = ((BigInteger) values.getIndexedValues().get(0).getValue()).longValueExact();
        String client = (String) values.getIndexedValues().get(1).getValue();
        String freelancer = (String) values.getIndexedValues().get(2).getValue();
        String arbiter = (String) values.getNonIndexedValues().get(0).getValue();
        BigInteger totalAmount = (BigInteger) values.getNonIndexedValues().get(1).getValue();

        if (dealRepository.existsById(dealId)) {
            return; // already recorded (e.g. listener restarted and re-scanned old blocks)
        }

        DealRecord record = new DealRecord(dealId, client, freelancer, arbiter, totalAmount.toString(), Instant.now());
        dealRepository.save(record);

        int milestoneCount = chainReader.getMilestoneCount(dealId);
        for (int i = 0; i < milestoneCount; i++) {
            ChainReaderService.MilestoneInfo m = chainReader.getMilestone(dealId, i);
            milestoneRepository.save(new MilestoneRecord(dealId, i, m.amount().toString()));
        }

        log.info("Recorded new deal {} ({} <-> {}), {} milestones", dealId, client, freelancer, milestoneCount);
    }

    private void onMilestoneApproved(Log eventLog) {
        EventValues values = Contract.staticExtractEventParameters(MILESTONE_APPROVED, eventLog);
        long dealId = ((BigInteger) values.getIndexedValues().get(0).getValue()).longValueExact();
        int milestoneIndex = ((BigInteger) values.getIndexedValues().get(1).getValue()).intValueExact();

        updateMilestoneStatus(dealId, milestoneIndex, MilestoneRecord.MilestoneStatus.RELEASED);
        recomputeDealTotals(dealId);

        dealRepository.findById(dealId).ifPresent(deal ->
                notificationService.notifyMilestoneApproved(dealId, milestoneIndex, deal.getFreelancerAddress()));
    }

    private void onDisputeRaised(Log eventLog) {
        EventValues values = Contract.staticExtractEventParameters(DISPUTE_RAISED, eventLog);
        long dealId = ((BigInteger) values.getIndexedValues().get(0).getValue()).longValueExact();
        int milestoneIndex = ((BigInteger) values.getIndexedValues().get(1).getValue()).intValueExact();
        String raisedBy = (String) values.getNonIndexedValues().get(0).getValue();

        updateMilestoneStatus(dealId, milestoneIndex, MilestoneRecord.MilestoneStatus.DISPUTED);
        notificationService.notifyDisputeRaised(dealId, milestoneIndex, raisedBy);
    }

    private void onDisputeResolved(Log eventLog) {
        EventValues values = Contract.staticExtractEventParameters(DISPUTE_RESOLVED, eventLog);
        long dealId = ((BigInteger) values.getIndexedValues().get(0).getValue()).longValueExact();
        int milestoneIndex = ((BigInteger) values.getIndexedValues().get(1).getValue()).intValueExact();
        boolean releasedToFreelancer = (Boolean) values.getNonIndexedValues().get(0).getValue();

        updateMilestoneStatus(dealId, milestoneIndex, MilestoneRecord.MilestoneStatus.RELEASED);
        recomputeDealTotals(dealId);
        notificationService.notifyDisputeResolved(dealId, milestoneIndex, releasedToFreelancer);
    }

    private void onDealCancelled(Log eventLog) {
        EventValues values = Contract.staticExtractEventParameters(DEAL_CANCELLED, eventLog);
        long dealId = ((BigInteger) values.getIndexedValues().get(0).getValue()).longValueExact();

        dealRepository.findById(dealId).ifPresent(deal -> {
            deal.setStatus(DealRecord.DealStatus.CANCELLED);
            deal.touchSync();
            dealRepository.save(deal);
        });
    }

    private void onDealCompleted(Log eventLog) {
        EventValues values = Contract.staticExtractEventParameters(DEAL_COMPLETED, eventLog);
        long dealId = ((BigInteger) values.getIndexedValues().get(0).getValue()).longValueExact();

        dealRepository.findById(dealId).ifPresent(deal -> {
            deal.setStatus(DealRecord.DealStatus.COMPLETED);
            deal.touchSync();
            dealRepository.save(deal);
        });

        notificationService.notifyDealCompleted(dealId);
    }

    private void updateMilestoneStatus(long dealId, int milestoneIndex, MilestoneRecord.MilestoneStatus status) {
        Optional<MilestoneRecord> record = milestoneRepository.findByOnChainDealIdAndMilestoneIndex(dealId, milestoneIndex);
        record.ifPresent(m -> {
            m.setStatus(status);
            milestoneRepository.save(m);
        });
    }

    private void recomputeDealTotals(long dealId) {
        dealRepository.findById(dealId).ifPresent(deal -> {
            ChainReaderService.DealInfo info = chainReader.getDeal(dealId);
            deal.setReleasedAmountWei(info.releasedAmount().toString());
            deal.touchSync();
            dealRepository.save(deal);
        });
    }
}
