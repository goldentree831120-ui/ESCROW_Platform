// Mutation check: undo each security fix in MilestoneEscrowV2 one at a time and confirm the test
// suite FAILS. A fix whose removal no test notices is a fix nobody is protecting.
//   node scripts/mutation-check.js          (exit code 1 if any mutant survives)
const fs = require("fs");
const path = require("path");
const { spawnSync } = require("child_process");

const FILE = path.join(__dirname, "..", "contracts", "MilestoneEscrowV2.sol");
const CONFIG = process.env.HARDHAT_CONFIG ? ["--config", process.env.HARDHAT_CONFIG] : [];

const MUTANTS = [
  ["[E1] client may cancel after acceptance",
    'require(deals[dealId].status == DealStatus.Proposed, "deal already accepted: use a dispute");',
    'require(deals[dealId].status == DealStatus.Proposed || deals[dealId].status == DealStatus.Active, "deal already accepted: use a dispute");'],
  ["[E1] deals start Active (no acceptance step)",
    "status: DealStatus.Proposed, // [E1][E2]",
    "status: DealStatus.Active, // [E1][E2]"],
  ["[E2] client may be its own arbiter",
    "arbiter != address(0) && arbiter != msg.sender && arbiter != freelancer",
    "arbiter != address(0) && arbiter != freelancer"],
  ["[E2] freelancer may be the arbiter",
    "arbiter != address(0) && arbiter != msg.sender && arbiter != freelancer",
    "arbiter != address(0) && arbiter != msg.sender"],
  ["[E3] expired-dispute split ignores the timeout",
    "block.timestamp >= uint256(m.disputedAt) + DISPUTE_TIMEOUT",
    "block.timestamp >= uint256(m.disputedAt)"],
  ["[E3] 50/50 split loses the odd wei",
    "uint256 toClient = m.amount - toFreelancer;",
    "uint256 toClient = toFreelancer;"],
  ["[E4] resolving for the client credits the freelancer",
    "else _settle(dealId, milestoneIndex, 0, m.amount);",
    "else _settle(dealId, milestoneIndex, m.amount, 0);"],
  ["[E4] withdraw doesn't clear the balance",
    "owed[msg.sender] = 0; // [E5]",
    "// owed[msg.sender] = 0; // [E5]"],
  ["[E4] totalOwed not reduced on withdraw",
    "totalOwed -= amount;",
    "// totalOwed -= amount;"],
  ["deal never reaches Completed",
    "deal.settledAmount += m.amount;",
    "deal.settledAmount += 0;"],
  ["[E6] unknown deals accepted (inStatus check removed from raiseDispute)",
    "function raiseDispute(uint256 dealId, uint256 milestoneIndex) external inStatus(dealId, DealStatus.Active) {",
    "function raiseDispute(uint256 dealId, uint256 milestoneIndex) external {"],
];

const original = fs.readFileSync(FILE, "utf8");
let survived = 0;
try {
  for (const [name, from, to] of MUTANTS) {
    const count = original.split(from).length - 1;
    if (count !== 1) {
      console.log(`??        ${name}: pattern found ${count} times`);
      survived++;
      continue;
    }
    fs.writeFileSync(FILE, original.replace(from, to));
    const r = spawnSync("npx", ["hardhat", ...CONFIG, "test"], { cwd: path.join(__dirname, ".."), encoding: "utf8" });
    if (r.status === 0) {
      console.log(`SURVIVED  ${name}`);
      survived++;
    } else {
      console.log(`caught    ${name}`);
    }
  }
} finally {
  fs.writeFileSync(FILE, original);
}
console.log(`${MUTANTS.length - survived}/${MUTANTS.length} caught`);
process.exit(survived ? 1 : 0);
