// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import "@openzeppelin/contracts/utils/ReentrancyGuard.sol";

/// @title MilestoneEscrow
/// @notice Holds a client's ETH in escrow against a list of milestones for a
///         freelancer, releasing each milestone's payment only when the
///         client approves it - or letting a neutral arbiter decide if the
///         client and freelancer disagree. This models a real product
///         feature (milestone-based payments), not just a generic escrow,
///         which is what makes it a good fit for an "enterprise backend +
///         blockchain" style project: the interesting problems are in the
///         state machine and the off-chain system that watches it, not in
///         the transfer of funds itself.
contract MilestoneEscrow is ReentrancyGuard {
    enum DealStatus {
        Active,
        Completed,
        Cancelled
    }

    struct Milestone {
        uint256 amount;
        bool released;
        bool disputed;
    }

    struct Deal {
        address client;
        address freelancer;
        address arbiter;
        uint256 totalAmount;
        uint256 releasedAmount;
        DealStatus status;
        uint256 createdAt;
    }

    uint256 public nextDealId;
    mapping(uint256 => Deal) public deals;
    mapping(uint256 => Milestone[]) public milestones;

    event DealCreated(uint256 indexed dealId, address indexed client, address indexed freelancer, address arbiter, uint256 totalAmount, uint256 milestoneCount);
    event MilestoneApproved(uint256 indexed dealId, uint256 indexed milestoneIndex, uint256 amount);
    event DisputeRaised(uint256 indexed dealId, uint256 indexed milestoneIndex, address raisedBy);
    event DisputeResolved(uint256 indexed dealId, uint256 indexed milestoneIndex, bool releasedToFreelancer);
    event DealCancelled(uint256 indexed dealId, uint256 refundedAmount);
    event DealCompleted(uint256 indexed dealId);

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

    modifier dealActive(uint256 dealId) {
        require(deals[dealId].status == DealStatus.Active, "deal not active");
        _;
    }

    /// @notice Client creates a deal, funding it up-front with ETH equal to
    ///         the sum of every milestone amount. `arbiter` should be a
    ///         mutually-trusted third party (or a DAO/multisig in a real
    ///         deployment) who only ever acts if a milestone is disputed.
    function createDeal(
        address freelancer,
        address arbiter,
        uint256[] calldata milestoneAmounts
    ) external payable returns (uint256 dealId) {
        require(freelancer != address(0) && freelancer != msg.sender, "invalid freelancer");
        require(arbiter != address(0), "invalid arbiter");
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
            releasedAmount: 0,
            status: DealStatus.Active,
            createdAt: block.timestamp
        });

        for (uint256 i = 0; i < milestoneAmounts.length; i++) {
            milestones[dealId].push(Milestone({amount: milestoneAmounts[i], released: false, disputed: false}));
        }

        emit DealCreated(dealId, msg.sender, freelancer, arbiter, total, milestoneAmounts.length);
    }

    /// @notice Client signs off on a milestone; funds go straight to the freelancer.
    function approveMilestone(uint256 dealId, uint256 milestoneIndex)
        external
        nonReentrant
        onlyClient(dealId)
        dealActive(dealId)
    {
        Milestone storage m = milestones[dealId][milestoneIndex];
        require(!m.released, "already released");
        require(!m.disputed, "milestone is disputed");

        _release(dealId, milestoneIndex, deals[dealId].freelancer);
        emit MilestoneApproved(dealId, milestoneIndex, m.amount);
    }

    /// @notice Either party can flag a milestone as disputed, freezing it until the arbiter rules.
    function raiseDispute(uint256 dealId, uint256 milestoneIndex) external dealActive(dealId) {
        Deal storage deal = deals[dealId];
        require(msg.sender == deal.client || msg.sender == deal.freelancer, "not a party to this deal");

        Milestone storage m = milestones[dealId][milestoneIndex];
        require(!m.released, "already released");
        require(!m.disputed, "already disputed");

        m.disputed = true;
        emit DisputeRaised(dealId, milestoneIndex, msg.sender);
    }

    /// @notice Arbiter resolves a disputed milestone one way or the other. This is
    ///         the manual-review path a real platform would pair with off-chain
    ///         evidence (the backend is where that evidence - messages, delivered
    ///         files, git commits - would actually live).
    function resolveDispute(uint256 dealId, uint256 milestoneIndex, bool releaseToFreelancer)
        external
        nonReentrant
        onlyArbiter(dealId)
        dealActive(dealId)
    {
        Milestone storage m = milestones[dealId][milestoneIndex];
        require(m.disputed, "milestone is not disputed");
        require(!m.released, "already released");

        address recipient = releaseToFreelancer ? deals[dealId].freelancer : deals[dealId].client;
        _release(dealId, milestoneIndex, recipient);
        emit DisputeResolved(dealId, milestoneIndex, releaseToFreelancer);
    }

    /// @notice Client can cancel and reclaim any milestones not yet released,
    ///         as long as none are currently under dispute.
    function cancelDeal(uint256 dealId) external nonReentrant onlyClient(dealId) dealActive(dealId) {
        Milestone[] storage ms = milestones[dealId];
        uint256 refund = 0;

        for (uint256 i = 0; i < ms.length; i++) {
            require(!ms[i].disputed, "resolve disputes before cancelling");
            if (!ms[i].released) {
                refund += ms[i].amount;
                ms[i].released = true; // mark settled so it can't be double counted
            }
        }

        deals[dealId].status = DealStatus.Cancelled;

        if (refund > 0) {
            (bool ok, ) = payable(deals[dealId].client).call{value: refund}("");
            require(ok, "refund transfer failed");
        }

        emit DealCancelled(dealId, refund);
    }

    function milestonesOf(uint256 dealId) external view returns (Milestone[] memory) {
        return milestones[dealId];
    }

    function milestoneCount(uint256 dealId) external view returns (uint256) {
        return milestones[dealId].length;
    }

    function _release(uint256 dealId, uint256 milestoneIndex, address recipient) private {
        Milestone storage m = milestones[dealId][milestoneIndex];
        Deal storage deal = deals[dealId];

        m.released = true;
        deal.releasedAmount += m.amount;

        (bool ok, ) = payable(recipient).call{value: m.amount}("");
        require(ok, "payment transfer failed");

        if (deal.releasedAmount == deal.totalAmount) {
            deal.status = DealStatus.Completed;
            emit DealCompleted(dealId);
        }
    }
}
