# Milestone Escrow Platform

A milestone-based Ethereum escrow system built with **Solidity, Spring Boot, and Web3j**.

The idea is simple: a client deposits ETH for a freelance job, the payment is split into milestones, and funds are released as each milestone is approved. If something goes wrong, either party can raise a dispute and an arbiter can decide where the money goes.

What makes this project different from a typical Solidity demo is the backend around the contract. The Spring Boot application handles authentication, stores business metadata, listens for on-chain events, and keeps the database synchronized with what actually happened on Ethereum.

The contract and backend have been deployed and tested end-to-end on **Ethereum Sepolia**, rather than only running against a local blockchain.

---

## Why I Built This

A staking contract or NFT marketplace is a good way to learn Solidity, but I wanted to explore a slightly different problem:

> **What does it actually look like when a smart contract becomes part of a real application?**

The interesting part here isn't just writing the escrow contract. The harder problem is what happens around it.

A backend needs to know when money moves on-chain, even though it doesn't control those transactions. It needs to associate blockchain events with users and database records, provide authentication and business metadata, and recover gracefully if the blockchain listener goes down.

That led to a fairly realistic architecture:

**User → Spring Boot API → Database**
**             ↕**
**        Ethereum Contract**
**             ↑**
**        User's Wallet**

The backend never takes custody of the user's funds. Transactions that move ETH are signed by the user's own wallet.

---

## Tech Stack

* **Solidity** — escrow contract
* **Hardhat** — compilation, testing, coverage and deployment
* **Java / Spring Boot** — backend API
* **Spring Security + JWT** — authentication
* **Web3j** — Ethereum integration and event processing
* **H2 / PostgreSQL** — database
* **ethers.js + MetaMask** — frontend blockchain interaction
* **Ethereum Sepolia** — deployed testnet

---

## Project Structure

```text
contracts/
├── contracts/MilestoneEscrow.sol      the deployed contract (unchanged)
├── contracts/MilestoneEscrowV2.sol    the same contract with the security findings fixed
├── contracts/test-helpers/            a contract "client" used by the attack tests
├── test/                              original tests, attack tests, randomized invariant test
└── scripts/mutation-check.js          undoes each V2 fix to prove a test catches it

security/                              threat model, Slither triage, raw Slither reports
SECURITY.md                            summary of the review

backend/
├── src/main/java/
│   └── ...
└── src/main/resources/static/
    └── index.html

scripts/
└── demo.js
```

---

# 1. Smart Contract

The main contract is `MilestoneEscrow.sol`.

A client creates a deal by depositing ETH upfront and defining the milestones, freelancer, and arbiter.

### How the escrow works

1. The client creates a deal and deposits the required ETH.
2. The payment is divided between named milestones.
3. The client can approve a milestone and release its funds to the freelancer.
4. Either party can raise a dispute on an unreleased milestone.
5. A disputed milestone is frozen until the arbiter resolves it.
6. The arbiter can award the disputed funds to either the client or freelancer.
7. The client can cancel a deal and recover unreleased funds, provided there are no unresolved disputes.
8. Once every milestone has been released, the deal is automatically marked as completed.

The contract intentionally keeps the business logic on-chain while leaving things such as titles, descriptions, and user accounts to the backend.

### Contract tests

The contract currently has:

* **10/10 tests passing**
* **93.75% statement coverage**
* **94% line coverage**

The tests cover deal creation, deposit validation, milestone approval, completion, access control, disputes, arbiter resolution, and cancellation.

Run them with:

```bash
cd contracts

npm install

cp .env.example .env
# Add SEPOLIA_RPC_URL and PRIVATE_KEY

npx hardhat compile
npx hardhat test
npx hardhat coverage
```

To deploy:

```bash
npx hardhat run scripts/deploy.js --network sepolia
```

---

# 2. Backend

The backend is built with **Spring Boot** and is responsible for everything around the smart contract.

It handles:

* JWT authentication
* User accounts and wallet addresses
* Deal and milestone records
* Business metadata
* Blockchain event processing
* REST APIs
* Keeping the database synchronized with on-chain activity

Start it with:

```bash
cd backend

export WEB3_RPC_URL=https://eth-sepolia.g.alchemy.com/v2/YOUR_KEY
export ESCROW_ADDRESS=0xC7DC6F5adf468C06b3fAD2298ee50747848EB5C4
export START_BLOCK=<deployment block number>
export JWT_SECRET=$(openssl rand -hex 32)

mvn spring-boot:run
```

Then open:

```text
http://localhost:8081
```

By default, the application uses an in-memory H2 database.

For a persistent database, the PostgreSQL configuration in `application.yml` can be enabled.

---

## Event Listener

The most interesting part of the backend is `EscrowEventListenerService`.

The smart contract emits events such as:

```text
DealCreated
MilestoneApproved
DisputeRaised
DisputeResolved
DealCancelled
DealCompleted
```

The backend reads these events and updates its own database.

For example:

```text
Ethereum
   │
   │ DealCreated
   ▼
Event Listener
   │
   ▼
DealRecord + MilestoneRecord
   │
   ▼
REST API
   │
   ▼
Dashboard
```

This means the database isn't treated as the source of truth for money movement. The blockchain is.

The database stores the application-level representation of what happened on-chain.

---

# A Real Bug I Ran Into

One of the most useful parts of building this project was discovering a failure that looked completely harmless at first.

The first version of the listener used Web3j's:

```java
ethLogFlowable(EthFilter)
```

This relies on the RPC provider maintaining the state of an Ethereum filter between requests.

That worked locally, but it didn't behave correctly with Alchemy.

The problem was that Alchemy load-balances requests across backend nodes. A later request asking for filter changes could reach a different node from the one that created the filter.

The result was particularly annoying:

**No exception. No stack trace. No obvious error. Just no events.**

The demo transactions were successfully going through on-chain, but the backend database remained empty.

After tracing the problem, I replaced the stateful filter with a stateless polling approach.

The listener now:

1. Keeps track of the last block it processed.
2. Calls `eth_getLogs` with an explicit block range.
3. Processes the returned events.
4. Saves the latest processed block.
5. Repeats every 5 seconds.

There is another practical detail here: RPC providers can limit the block range accepted by `eth_getLogs`. The listener therefore processes the chain in small chunks instead of requesting a huge range in one call.

This version successfully picked up all events from the live Sepolia demo.

---

# 3. REST API

The backend exposes a small API for authentication and deal metadata.

| Method | Endpoint                                 | Auth | Description                   |
| ------ | ---------------------------------------- | ---- | ----------------------------- |
| POST   | `/api/auth/register`                     | No   | Create an account             |
| POST   | `/api/auth/login`                        | No   | Login and receive a JWT       |
| GET    | `/api/deals/mine`                        | Yes  | Get your deals                |
| GET    | `/api/deals/{dealId}`                    | Yes  | Get a deal and its milestones |
| PATCH  | `/api/deals/{dealId}`                    | Yes  | Update deal metadata          |
| PATCH  | `/api/deals/{dealId}/milestones/{index}` | Yes  | Update milestone metadata     |

The contract doesn't need to know about things like a project's title or description, so those fields live in the backend database.

---

# 4. Frontend

The frontend is intentionally simple.

It's a single dependency-free HTML/JavaScript page located at:

```text
backend/src/main/resources/static/index.html
```

It uses:

* **MetaMask** for wallet transactions
* **ethers.js** for interacting with the contract
* **Spring Boot APIs** for authentication and metadata

The basic flow is:

```text
Register / Login
       ↓
Connect MetaMask
       ↓
Create Deal
       ↓
MetaMask signs transaction
       ↓
Ethereum
       ↓
Backend detects DealCreated
       ↓
Database updated
       ↓
Dashboard refreshed
```

Before running the frontend, set the deployed contract address in the `ESCROW_ADDRESS` constant inside `index.html`.

---

# Live Deployment

The contract is deployed on **Ethereum Sepolia**:

```text
MilestoneEscrow
0xC7DC6F5adf468C06b3fAD2298ee50747848EB5C4
```

The deployment was tested using the complete deal lifecycle.

---

# End-to-End Demo

The included `scripts/demo.js` runs through the full flow against the deployed contract:

```text
Create deal
    ↓
Approve milestone 1
    ↓
Raise dispute on milestone 2
    ↓
Arbiter resolves dispute
    ↓
Deal becomes completed
```

The backend picked up the events and reconstructed the deal in its database.

The final API response looked like:

```json
{
  "status": "COMPLETED",
  "totalAmountEth": 0.0025,
  "releasedAmountEth": 0.0025,
  "milestones": [
    {
      "index": 0,
      "amountEth": 0.001,
      "status": "RELEASED"
    },
    {
      "index": 1,
      "amountEth": 0.0015,
      "status": "RELEASED"
    }
  ]
}
```

So the complete flow was verified:

**deal creation → milestone approval → dispute → arbiter resolution → completion**

with the final state correctly reflected in both Ethereum and the backend database.

---

# A Few Engineering Decisions

### The backend doesn't hold user funds

The backend never stores a user's private key and never signs transactions on their behalf.

When ETH needs to move, the user's wallet signs the transaction.

The backend only:

* authenticates the user
* reads blockchain activity
* stores application metadata
* exposes that information through the API

This keeps the trust boundary much smaller.

---

### Events vs. polling

The project uses blockchain events to answer:

> "Did something happen?"

For example, when a milestone is approved, the contract emits an event and the backend records it.

That's different from a system that simply polls blockchain state and asks:

> "What does this wallet look like right now?"

Both approaches are useful, but they solve slightly different problems.

---

### Idempotency and chain reorganizations

The listener avoids processing the same deal twice, which also helps when the listener restarts and scans previously processed blocks.

There is still an edge case worth addressing in a production system: blockchain reorganizations.

A stronger implementation would track processed events using:

```text
(transactionHash, logIndex)
```

rather than relying only on the deal ID.

That would make event processing more robust against reorgs and replayed events.

---

# What I Learned

This project ended up being less about writing a Solidity contract and more about understanding the boundary between a blockchain and a normal backend application.

A few things stood out:

* On-chain state and off-chain state need a reliable synchronization strategy.
* RPC behavior matters just as much as smart-contract logic.
* A failure doesn't always come with an exception — sometimes the system simply stops receiving data.
* Blockchain events are useful, but they need to be processed with the same reliability considerations as any other distributed system.
* Keeping private keys out of the backend significantly changes the trust model of the application.
* Smart contracts should store the state that actually needs to be trustless; normal application metadata doesn't necessarily belong on-chain.

---

# Security Review

[![Contracts](https://github.com/goldentree831120-ui/ESCROW_Platform/actions/workflows/contracts.yml/badge.svg)](https://github.com/goldentree831120-ui/ESCROW_Platform/actions/workflows/contracts.yml)

I reviewed my own contract the way an auditor would: a threat model, Slither with every finding
triaged, and tests that actually run each attack. Summary in [`SECURITY.md`](SECURITY.md), details in
[`security/THREAT_MODEL.md`](security/THREAT_MODEL.md).

What the attack tests showed about the deployed contract:

* **The client can cancel after the work is delivered** and take back every unreleased milestone,
  and can front-run the freelancer's dispute (the test puts both transactions in one block).
* **The client can be its own arbiter**; nothing checks who the arbiter is, and the freelancer never
  agrees to it.
* **A silent arbiter locks the money forever**: one open dispute also blocks cancelling, so every
  other unreleased milestone is stuck too.
* **Reentrancy is properly defended**, and a randomized test (250 random actions) found the
  contract's balance equal to what it still owes after every single action.

`MilestoneEscrowV2.sol` fixes these: the freelancer must accept the deal (and the arbiter) first,
after which the client can't cancel; the arbiter must be a third party; a dispute the arbiter
ignores for 30 days can be split 50/50; and payouts are withdrawn rather than pushed. A mutation
check undoes each fix in turn and the tests catch all 11. The deployed contract can't be patched,
so V2 would be a new deployment.

```bash
cd contracts && npm ci
npx hardhat test                  # 29 tests
node scripts/mutation-check.js    # 11/11 planted regressions caught
```

---

# Possible Next Steps

There are a few directions I'd take this project next:

* Add **ERC-20 / USDC payments** alongside ETH.
* Automatically detect the deployment block instead of requiring `START_BLOCK`.
* Persist processed `(transactionHash, logIndex)` pairs.
* Add stronger recovery and retry handling to the event listener.
* Support a WebSocket RPC connection for lower-latency event processing.
* Replace H2 with PostgreSQL in the deployed environment.
* Verify wallet ownership at registration with Sign-In with Ethereum (finding B1 in the security review).
* Deploy `MilestoneEscrowV2` and teach the event listener its new events.
* Add more comprehensive integration tests covering backend ↔ blockchain synchronization.

---

## Summary

This project started as an escrow smart contract, but the main goal was to explore what happens when that contract becomes part of an actual backend application.

The result is a small end-to-end system where:

* **Solidity** handles the money and escrow rules.
* **Ethereum** provides the source of truth for fund movement.
* **Spring Boot** handles users, APIs, and business metadata.
* **Web3j** connects the backend to the blockchain.
* **The event listener** reconciles on-chain activity with the database.
* **MetaMask** keeps transaction signing in the user's hands.

It's intentionally small enough to understand end-to-end, while still dealing with some of the problems that show up when blockchain code meets a real backend.
