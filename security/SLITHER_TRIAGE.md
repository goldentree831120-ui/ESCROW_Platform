# Slither triage

Slither 0.11.3, solc 0.8.24, `--exclude-dependencies`. Raw output in `reports/`.

```bash
cd contracts && npm ci
slither contracts/MilestoneEscrow.sol --solc-remaps "@openzeppelin/contracts/=node_modules/@openzeppelin/contracts/" --exclude-dependencies
```

## Original: `MilestoneEscrow.sol` (19 results: 9 in this contract, 10 in OpenZeppelin)

| Detector | Impact | Where | Verdict | Reasoning |
|---|---|---|---|---|
| `arbitrary-send-eth` | High | `_release` | **False positive, by design** | The recipient is always the deal's freelancer or client, chosen by the code, not by the caller. The real problem around payouts is who decides (E1, E2), which Slither can't see. |
| `reentrancy-eth` | High | `_release`: `status = Completed` written after the ETH `call` | **Real pattern, not exploitable** (E5) | The milestone is marked released and `releasedAmount` updated before the call; `approveMilestone`, `resolveDispute`, `cancelDeal` are `nonReentrant`. The only unguarded state-changing function is `raiseDispute`, which can't touch a released milestone: tested by re-entering it during the last milestone's payment (`E5` tests). Fixed in V2 anyway (no ETH sent during settlement). |
| `incorrect-equality` | Medium | `dealActive`: `status == Active` | **False positive** | The detector targets `==` on balances/timestamps that an attacker can nudge; this is an enum compared with an enum. |
| `timestamp` (×3) | Low | `raiseDispute`, `cancelDeal`, `_release` | **False positive** | None of these functions reads `block.timestamp`; the "dangerous comparisons" Slither lists are an address check (`msg.sender == deal.client ...`) and two `require(ok, ...)` checks after sending ETH. Whatever triggers the detector, no timestamp is compared. |
| `pragma` | Info | `^0.8.24` here, `^0.8.20` in OpenZeppelin | **Accepted** | Different ranges in a dependency are normal. Compiled with exactly 0.8.24 (`hardhat.config.js`). V2 pins `0.8.24`. |
| `low-level-calls` (×2) | Info | `cancelDeal`, `_release` | **Accepted** | `call{value:}` with a checked result is the recommended way to send ETH (no 2300-gas stipend problem). The consequence of a failing recipient is E4. |

OpenZeppelin results (`assembly` ×9 in `StorageSlot`, `solc-version` for `^0.8.20`): library code;
the listed compiler bugs don't affect 0.8.24, which is what is used.

## Fixed: `MilestoneEscrowV2.sol` (20 results: 10 in this contract)

| Detector | Verdict |
|---|---|
| `arbitrary-send-eth`, `reentrancy-eth` | **Gone.** No ETH is sent during settlement; `withdraw` pays `msg.sender` only, after clearing its balance. |
| `incorrect-equality` (×3) | False positive: enum comparisons (`inStatus`, `cancelDeal`). |
| `timestamp` (×5) | Four false positives as above. One real use: `resolveExpiredDispute` compares `block.timestamp` with `disputedAt + 30 days`. A validator can shift a timestamp by seconds, which doesn't matter against a 30-day window. **Accepted.** |
| `pragma`, `low-level-calls` | Accepted, as above (V2's one call is in `withdraw`). |

## CI gate

`.github/workflows/contracts.yml` runs Slither on V2, excluding only the detectors triaged above as
false positives or accepted (`incorrect-equality, timestamp, pragma, low-level-calls, solc-version,
assembly`), with `--fail-low`. V2 has 0 remaining findings, so any new finding fails the build.
The original isn't gated: it is the deployed version and its two High results are documented above.
