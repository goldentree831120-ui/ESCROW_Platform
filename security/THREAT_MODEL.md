# Threat model: MilestoneEscrow

Scope: `contracts/contracts/MilestoneEscrow.sol` (the version deployed on Sepolia at
`0xC7DC6F5adf468C06b3fAD2298ee50747848EB5C4`), with a short section on the Spring Boot backend.
Testnet project; no real funds. Every finding below has a test in
`contracts/test/MilestoneEscrow.attacks.test.js` that runs the attack against the original and
against the fixed `MilestoneEscrowV2.sol`.

## What the contract does

A client funds a deal up front with ETH equal to the sum of its milestones. For each milestone the
client either approves it (payment to the freelancer) or either party disputes it, and then the
arbiter sends it to the freelancer or back to the client. The client can cancel and take back
everything not yet released, unless a milestone is under dispute.

## Assets

| Asset | Where |
|---|---|
| Escrowed ETH | the contract's balance: all unreleased milestones of active deals |
| The freelancer's claim to payment for delivered work | only as strong as the rules for releasing / refunding |
| Fairness of a dispute | depends entirely on who the arbiter is |
| Deal descriptions (off-chain) | backend database; they are the evidence in a dispute |

## Actors and trust

| Actor | Can | Trusted to |
|---|---|---|
| Client | create, approve, dispute, cancel | pay for delivered work |
| Freelancer | dispute | deliver |
| Arbiter | resolve disputed milestones only | be neutral |
| Anyone | call any function, watch the mempool, deploy contracts as client/freelancer | nothing |

**Trust boundary that matters most:** the arbiter is chosen by the client alone, and the
freelancer never has to agree to anything on-chain. The original contract therefore protects the
client's money well and the freelancer's earned money hardly at all.

## Attack paths considered

| Attack | Result on the original | ID |
|---|---|---|
| Client cancels after the work is delivered | **works**: everything unreleased goes back to the client | E1 |
| Freelancer disputes, client front-runs with `cancelDeal` | **works**: test orders both in one block, cancel first, dispute reverts | E1 |
| Client names itself (or the freelancer, or a friend) as arbiter | **works**: no checks; client-arbiter rules for itself | E2 |
| Arbiter disappears after a dispute | **funds locked forever**: the disputed milestone, AND every other unreleased one, because cancel is blocked while any dispute is open | E3 |
| Freelancer disputes one milestone to stop the client cancelling | **works** (griefing), same mechanism as E3 | E3 |
| Recipient contract refuses ETH | the arbiter's ruling for that party becomes impossible; the other party wins by default | E4 |
| Reentrancy while being paid (refund, release) | **blocked**: `nonReentrant` on every paying function, and state is updated before paying | E5 |
| Act on a deal ID that doesn't exist | blocked today (no matching client/arbiter), but such IDs read as `Active` | E6 |
| Non-party approves / resolves / disputes | blocked (role modifiers); also exercised in the randomized test | — |
| Pay out more than was deposited | not found: randomized test checks balance == unreleased amounts after every one of 250 random actions | — |
| Rounding | none: amounts are whole wei, no division | — |
| Unbounded loop in `cancelDeal` | only the client chooses the milestone count, so only the client can hurt itself | E7 |

## Findings

| ID | Severity | Finding | Fix in V2 |
|---|---|---|---|
| E1 | High | The client can cancel at any time and take back every unreleased milestone, including work already delivered. A freelancer's dispute can be front-run. | Deals start `Proposed`; the freelancer must `acceptDeal`. After acceptance the client can't cancel; money moves only by the client's approval, the arbiter, or the freelancer's `releaseClient` (mutual exit). |
| E2 | High | The arbiter is unchecked: it can be the client or the freelancer, and the freelancer never agrees to it. | `arbiter` must differ from both parties, and acceptance is the freelancer's agreement to that arbiter. |
| E3 | Medium | No timeout: an absent arbiter locks the disputed milestone and, by blocking `cancelDeal`, every other unreleased milestone. | `resolveExpiredDispute`: after 30 days anyone can settle the milestone 50/50 (odd wei to the client). |
| E4 | Low | Payments are pushed with `call`; a recipient that reverts on receiving ETH makes the ruling in its favour impossible. Affects mainly the party that refuses ETH. | Pull payments: settlements credit `owed[...]`, each party calls `withdraw()`. |
| E5 | Informational | `_release` sets `Completed` after sending ETH (Slither `reentrancy-eth`). Not exploitable: the milestone is marked released first, and the paying entry points are `nonReentrant`. | No ETH leaves the contract during settlement at all; `withdraw` clears the balance before paying. |
| E6 | Informational | `DealStatus` starts at `Active`, so unknown deal IDs read as active. Harmless with today's modifiers, a trap for future code. | `None` is the zero value. |
| E7 | Informational | Floating `pragma ^0.8.24`; unbounded loop in `cancelDeal` over a client-chosen milestone count. | V2 pins `0.8.24`. The loop is unchanged (only the caller can make it expensive for itself). |

**What V2 deliberately does not change:** the arbiter still decides disputes alone, so a dishonest
third-party arbiter can still rule unfairly. The fix is that both parties now agree to who it is,
and that it can't sit on a dispute forever.

## Off-chain (backend), not fixed in this review

| ID | Severity | Finding | Recommendation |
|---|---|---|---|
| B1 | Medium | Registration accepts any `walletAddress` without proof of ownership. Anyone can claim another user's wallet, then see that user's deals and edit their titles and milestone descriptions, which are the evidence in a dispute. | Sign-In with Ethereum (EIP-4361): the user signs a server nonce and the backend verifies the signature before linking the wallet. |
| B2 | Low | `GET /api/deals/{id}` returns any deal to any logged-in user (no party check), unlike the edit endpoints. | Apply the same `isPartyToDeal` check as the PATCH endpoints. |
| B3 | Low (deployment) | A demo JWT secret is used when `JWT_SECRET` isn't set; the H2 console is public with frame protection off. | Fail to start without `JWT_SECRET` outside local dev; disable the H2 console in any deployed profile. |
