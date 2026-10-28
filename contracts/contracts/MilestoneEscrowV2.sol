// SPDX-License-Identifier: MIT
pragma solidity 0.8.24;

import "@openzeppelin/contracts/utils/ReentrancyGuard.sol";

/// @title MilestoneEscrowV2
/// @notice MilestoneEscrow with the findings of security/THREAT_MODEL.md fixed. The original
///         (deployed on Sepolia) is unchanged; a deployed contract can't be patched, so fixes ship
///         as a new contract. Every change is marked with its finding ID:
///         [E1] the client can't cancel after the freelancer has accepted the deal
///         [E2] the arbiter must be a third party, and the freelancer must accept the terms
///         [E3] a dispute the arbiter never resolves splits 50/50 after DISPUTE_TIMEOUT
///         [E4] payouts are credited and withdrawn (pull payments), so a recipient that
///              rejects ETH can't block anyone else's settlement
///         [E5] state is final before any ETH leaves the contract
///         [E6] a deal ID that was never created is not "Active"
contract MilestoneEscrowV2 is ReentrancyGuard {
    enum DealStatus {
        None, // [E6] default for unknown IDs
        Proposed, // funded by the client, waiting for the freelancer to accept
        Active,
        Completed,
        Cancelled
    }

    struct Milestone {
        uint256 amount;
        bool released; // settled: paid (or credited) to someone, or refunded
        bool disputed;
        uint64 disputedAt;
    }

    struct Deal {
        address client;
        address freelancer;
        address arbiter;
        uint256 totalAmount;
        uint256 settledAmount;
        DealStatus status;
        uint256 createdAt;
    }

    /// [E3] How long the arbiter has to rule before anyone can split a disputed milestone 50/50.
    uint256 public constant DISPUTE_TIMEOUT = 30 days;

    uint256 public nextDealId;
    mapping(uint256 => Deal) public deals;
    mapping(uint256 => Milestone[]) public milestones;

    /// [E4] ETH that belongs to an address and can be withdrawn by it.
    mapping(address => uint256) public owed;
    uint256 public totalOwed;

    event DealCreated(uint256 indexed dealId, address indexed client, address indexed freelancer, address arbiter, uint256 totalAmount, uint256 milestoneCount);
    event DealAccepted(uint256 indexed dealId);
    event MilestoneApproved(uint256 indexed dealId, uint256 indexed milestoneIndex, uint256 amount);
    event DisputeRaised(uint256 indexed dealId, uint256 indexed milestoneIndex, address raisedBy);
    event DisputeResolved(uint256 indexed dealId, uint256 indexed milestoneIndex, bool releasedToFreelancer);
    event DisputeExpired(uint256 indexed dealId, uint256 indexed milestoneIndex, uint256 toFreelancer, uint256 toClient);
    event DealCancelled(uint256 indexed dealId, uint256 refundedAmount);
    event DealCompleted(uint256 indexed dealId);
    event Credited(address indexed account, uint256 amount);
    event Withdrawn(address indexed account, uint256 amount);

    modifier onlyClient(uint256 dealId) {
        require(msg.sender == deals[dealId].client, "not the client");
        _;
    }

    modifier onlyFreelancer(uint256 dealId) {
        require(msg.sender == deals[dealId].freelancer, "not the freelancer");
        _;
    }

    modifier onlyArbiter(uint256 dealId) {
        require(msg.sender == deals[dealId].arbiter, "not the arbiter");
        _;
    }

    modifier inStatus(uint256 dealId, DealStatus s) {
        require(deals[dealId].status == s, s == DealStatus.Active ? "deal not active" : "wrong deal status");
        _;
    }

    function createDeal(address freelancer, address arbiter, uint256[] calldata milestoneAmounts)
        external
        payable
        returns (uint256 dealId)
    {
        require(freelancer != address(0) && freelancer != msg.sender, "invalid freelancer");
        // [E2] The arbiter decides disputes between client and freelancer, so it can be neither.
        require(arbiter != address(0) && arbiter != msg.sender && arbiter != freelancer, "invalid arbiter");
        require(milestoneAmounts.length > 0, "need at least one milestone");

        uint256 total = 0;
        for (uint256 i = 0; i < milestoneAmounts.length; i++) {
            require(milestoneAmounts[i] > 0, "milestone amount must be > 0");
            total += milestoneAmounts[i];
        }
        require(msg.value == total, "deposit must equal sum of milestones");

        dealId = nextDealId++;
        deals[dealId] = Deal({
            client: msg.sender,
            freelancer: freelancer,
            arbiter: arbiter,
            totalAmount: total,
            settledAmount: 0,
            status: DealStatus.Proposed, // [E1][E2] nothing is binding until the freelancer accepts
            createdAt: block.timestamp
        });
        for (uint256 i = 0; i < milestoneAmounts.length; i++) {
            milestones[dealId].push(Milestone({amount: milestoneAmounts[i], released: false, disputed: false, disputedAt: 0}));
        }
        emit DealCreated(dealId, msg.sender, freelancer, arbiter, total, milestoneAmounts.length);
    }

    /// @notice [E1][E2] The freelancer agrees to the milestones AND to the arbiter. From now on
    ///         the client can no longer take the money back on its own.
    function acceptDeal(uint256 dealId) external onlyFreelancer(dealId) inStatus(dealId, DealStatus.Proposed) {
        deals[dealId].status = DealStatus.Active;
        emit DealAccepted(dealId);
    }

    function approveMilestone(uint256 dealId, uint256 milestoneIndex)
        external
        onlyClient(dealId)
        inStatus(dealId, DealStatus.Active)
    {
        Milestone storage m = milestones[dealId][milestoneIndex];
        require(!m.released, "already released");
        require(!m.disputed, "milestone is disputed");
        _settle(dealId, milestoneIndex, m.amount, 0);
        emit MilestoneApproved(dealId, milestoneIndex, m.amount);
    }

    function raiseDispute(uint256 dealId, uint256 milestoneIndex) external inStatus(dealId, DealStatus.Active) {
        Deal storage deal = deals[dealId];
        require(msg.sender == deal.client || msg.sender == deal.freelancer, "not a party to this deal");
        Milestone storage m = milestones[dealId][milestoneIndex];
        require(!m.released, "already released");
        require(!m.disputed, "already disputed");
        m.disputed = true;
        m.disputedAt = uint64(block.timestamp);
        emit DisputeRaised(dealId, milestoneIndex, msg.sender);
    }

    function resolveDispute(uint256 dealId, uint256 milestoneIndex, bool releaseToFreelancer)
        external
        onlyArbiter(dealId)
        inStatus(dealId, DealStatus.Active)
    {
        Milestone storage m = milestones[dealId][milestoneIndex];
        require(m.disputed, "milestone is not disputed");
        require(!m.released, "already released");
        if (releaseToFreelancer) _settle(dealId, milestoneIndex, m.amount, 0);
        else _settle(dealId, milestoneIndex, 0, m.amount);
        emit DisputeResolved(dealId, milestoneIndex, releaseToFreelancer);
    }

    /// @notice [E3] If the arbiter hasn't ruled within DISPUTE_TIMEOUT, anyone can settle the
    ///         milestone 50/50 (an odd wei goes to the client). Neither side can win by waiting.
    function resolveExpiredDispute(uint256 dealId, uint256 milestoneIndex) external inStatus(dealId, DealStatus.Active) {
        Milestone storage m = milestones[dealId][milestoneIndex];
        require(m.disputed, "milestone is not disputed");
        require(!m.released, "already released");
        require(block.timestamp >= uint256(m.disputedAt) + DISPUTE_TIMEOUT, "arbiter still has time");
        uint256 toFreelancer = m.amount / 2;
        uint256 toClient = m.amount - toFreelancer;
        _settle(dealId, milestoneIndex, toFreelancer, toClient);
        emit DisputeExpired(dealId, milestoneIndex, toFreelancer, toClient);
    }

    /// @notice [E1] The client can only cancel before the freelancer has accepted.
    function cancelDeal(uint256 dealId) external onlyClient(dealId) {
        require(deals[dealId].status == DealStatus.Proposed, "deal already accepted: use a dispute");
        _refundUnsettled(dealId);
    }

    /// @notice [E1] After acceptance, the freelancer can release the client (mutual exit):
    ///         every unsettled milestone goes back to the client. Not while a dispute is open.
    function releaseClient(uint256 dealId) external onlyFreelancer(dealId) inStatus(dealId, DealStatus.Active) {
        _refundUnsettled(dealId);
    }

    /// @notice [E4] Withdraw everything credited to msg.sender.
    function withdraw() external nonReentrant {
        uint256 amount = owed[msg.sender];
        require(amount > 0, "nothing to withdraw");
        owed[msg.sender] = 0; // [E5] effects before the external call
        totalOwed -= amount;
        (bool ok, ) = payable(msg.sender).call{value: amount}("");
        require(ok, "withdraw failed");
        emit Withdrawn(msg.sender, amount);
    }

    function milestonesOf(uint256 dealId) external view returns (Milestone[] memory) {
        return milestones[dealId];
    }

    function milestoneCount(uint256 dealId) external view returns (uint256) {
        return milestones[dealId].length;
    }

    function _refundUnsettled(uint256 dealId) private {
        Milestone[] storage ms = milestones[dealId];
        uint256 refund = 0;
        for (uint256 i = 0; i < ms.length; i++) {
            require(!ms[i].disputed || ms[i].released, "resolve disputes before cancelling");
            if (!ms[i].released) {
                refund += ms[i].amount;
                ms[i].released = true;
            }
        }
        Deal storage deal = deals[dealId];
        deal.settledAmount += refund;
        deal.status = DealStatus.Cancelled;
        _credit(deal.client, refund);
        emit DealCancelled(dealId, refund);
    }

    /// Marks one milestone settled and credits the two shares. No external calls: [E4][E5].
    function _settle(uint256 dealId, uint256 milestoneIndex, uint256 toFreelancer, uint256 toClient) private {
        Milestone storage m = milestones[dealId][milestoneIndex];
        Deal storage deal = deals[dealId];
        m.released = true;
        deal.settledAmount += m.amount;
        if (deal.settledAmount == deal.totalAmount) {
            deal.status = DealStatus.Completed;
            emit DealCompleted(dealId);
        }
        _credit(deal.freelancer, toFreelancer);
        _credit(deal.client, toClient);
    }

    function _credit(address account, uint256 amount) private {
        if (amount == 0) return;
        owed[account] += amount;
        totalOwed += amount;
        emit Credited(account, amount);
    }
}
