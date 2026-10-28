# Security

Security review of the escrow contract. Full threat model in
[`security/THREAT_MODEL.md`](security/THREAT_MODEL.md), Slither triage in
[`security/SLITHER_TRIAGE.md`](security/SLITHER_TRIAGE.md).

## Summary

| ID | Severity | Issue | Status |
|---|---|---|---|
| E1 | High | The client can cancel after the work is delivered and take back every unreleased milestone; it can also front-run the freelancer's dispute | Fixed in `MilestoneEscrowV2.sol` (freelancer accepts the deal; no client cancel afterwards) |
| E2 | High | The arbiter is unchecked: the client can be its own arbiter, and the freelancer never agrees to it | Fixed in V2 (third-party arbiter, accepted by the freelancer) |
| E3 | Medium | No timeout: a silent arbiter locks the disputed milestone and every other unreleased one | Fixed in V2 (50/50 split after 30 days) |
| E4 | Low | Push payments: a recipient that rejects ETH makes a ruling in its favour impossible | Fixed in V2 (pull payments) |
| E5–E7 | Informational | State written after an external call (not exploitable); unknown deal IDs read as Active; floating pragma | Fixed or documented in V2 |
| B1 | Medium | Backend: a wallet address is linked to an account without proof of ownership | **Open**: recommend Sign-In with Ethereum |
| B2, B3 | Low | Backend: any user can read any deal by ID; demo JWT secret and public H2 console if not configured | **Open** |

**The deployed contract** (`0xC7DC6F5adf468C06b3fAD2298ee50747848EB5C4`, Sepolia testnet) is the
original and can't be patched; V2 needs a new deployment, and the backend's event listener would
need V2's new events (`DealAccepted`, `DisputeExpired`, `Credited`, `Withdrawn`).

Defences that held in the original: reentrancy on every paying function, role checks on every
action, and exact accounting (randomized test: balance equals what's still owed after each of 250
random actions).

## Known limitations

- The arbiter still decides disputes alone. V2 makes both parties agree on who it is and stops it
  from stalling, but a dishonest agreed arbiter can still rule unfairly.
- After 30 days a dispute is split 50/50 regardless of merit; that's a fallback, not justice.
- Testnet portfolio project: no external audit, no formal verification.

## Run the checks

```bash
cd contracts
npm ci
npx hardhat test                      # 29 tests: original 10, attacks 17 (original and V2), 2 randomized
node scripts/mutation-check.js        # undoes each V2 fix in turn; the tests must catch all 11
pip install slither-analyzer==0.11.3 solc-select && solc-select install 0.8.24 && solc-select use 0.8.24
slither contracts/MilestoneEscrowV2.sol --solc-remaps "@openzeppelin/contracts/=node_modules/@openzeppelin/contracts/" --exclude-dependencies
```

CI (`.github/workflows/contracts.yml`) runs all three on every push.

## Reporting

This is a testnet portfolio project. Please report issues by opening a GitHub issue.
