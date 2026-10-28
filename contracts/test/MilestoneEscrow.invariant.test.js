// Randomized invariant test: hundreds of random actions by random accounts, and after EVERY one
// the contract's ETH balance must equal exactly what it still owes. Seeded, so a failure replays.
const { expect } = require("chai");
const { ethers } = require("hardhat");
const { time } = require("@nomicfoundation/hardhat-network-helpers");

const STEPS = Number(process.env.INVARIANT_STEPS || 250);
const SEED = Number(process.env.INVARIANT_SEED || 20261028);

function rng(seed) {
  // mulberry32
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

async function tryTx(p) {
  try {
    await (await p).wait();
    return true;
  } catch {
    return false; // reverts are expected: random actions are often not allowed
  }
}

for (const name of ["MilestoneEscrow", "MilestoneEscrowV2"]) {
  describe(`Invariant (${name}): balance == everything still owed, after every random action`, function () {
    this.timeout(300_000);

    it(`${STEPS} random actions, seed ${SEED}`, async function () {
      const v2 = name === "MilestoneEscrowV2";
      const c = await (await ethers.getContractFactory(name)).deploy();
      const addr = await c.getAddress();
      const s = await ethers.getSigners();
      const clients = s.slice(0, 3);
      const freelancers = s.slice(3, 6);
      const arbiters = s.slice(6, 8);
      const everyone = s.slice(0, 8);
      const r = rng(SEED);
      const pick = (arr) => arr[Math.floor(r() * arr.length)];
      const done = { create: 0, accept: 0, approve: 0, dispute: 0, resolve: 0, cancel: 0, release: 0, expire: 0, withdraw: 0 };

      async function check(step, action) {
        const n = Number(await c.nextDealId());
        let unsettled = 0n;
        for (let id = 0; id < n; id++) {
          const st = Number((await c.deals(id)).status);
          const open = v2 ? st === 1 || st === 2 : st === 0; // V2: Proposed/Active; original: Active
          if (!open) continue;
          for (const m of await c.milestonesOf(id)) if (!m.released) unsettled += m.amount;
        }
        let owed = 0n;
        if (v2) {
          for (const a of everyone) owed += await c.owed(a.address);
          expect(owed, `sum of owed == totalOwed (step ${step}, ${action})`).to.equal(await c.totalOwed());
        }
        expect(await ethers.provider.getBalance(addr), `balance (step ${step}, ${action})`).to.equal(unsettled + owed);
      }

      for (let step = 0; step < STEPS; step++) {
        const n = Number(await c.nextDealId());
        const id = n ? (v2 && r() < 0.6 ? n - 1 - Math.floor(r() * Math.min(n, 3)) : Math.floor(r() * n)) : 0;
        const deal = n ? await c.deals(id) : null;
        const count = n ? Number(await c.milestoneCount(id)) : 0;
        let idx = count ? Math.floor(r() * count) : 0;
        // For resolve/expire, aim at an open dispute when the deal has one (otherwise they always revert).
        const disputedIdx = async () => {
          const ms = n ? await c.milestonesOf(id) : [];
          const open = ms.map((m, i) => (m.disputed && !m.released ? i : -1)).filter((i) => i >= 0);
          return open.length ? open[Math.floor(r() * open.length)] : idx;
        };
        const signerOf = (a) => everyone.find((x) => x.address === a);
        const roll = r();
        let action;

        // V2 needs an accept before most actions, so it gets more accepts and fewer creates.
        const w = v2 ? [0.1, 0.3, 0.45, 0.58, 0.72, 0.77, 0.83, 0.88] : [0.15, 0.15, 0.45, 0.6, 0.72, 0.8, 0.8, 0.8];
        if (n === 0 || roll < w[0]) {
          action = "create";
          const amounts = Array.from({ length: 1 + Math.floor(r() * 3) }, () => BigInt(1 + Math.floor(r() * 5)) * 10n ** 15n + BigInt(Math.floor(r() * 3)));
          const total = amounts.reduce((x, y) => x + y, 0n);
          if (await tryTx(c.connect(pick(clients)).createDeal(pick(freelancers).address, pick(arbiters).address, amounts, { value: total }))) done.create++;
        } else if (v2 && roll < w[1]) {
          action = "accept";
          if (await tryTx(c.connect(signerOf(deal.freelancer)).acceptDeal(id))) done.accept++;
        } else if (roll < w[2]) {
          action = "approve";
          if (await tryTx(c.connect(signerOf(deal.client)).approveMilestone(id, idx))) done.approve++;
        } else if (roll < w[3]) {
          action = "dispute";
          const by = r() < 0.5 ? deal.client : deal.freelancer;
          if (await tryTx(c.connect(signerOf(by)).raiseDispute(id, idx))) done.dispute++;
        } else if (roll < w[4]) {
          action = "resolve";
          idx = await disputedIdx();
          if (await tryTx(c.connect(signerOf(deal.arbiter)).resolveDispute(id, idx, r() < 0.5))) done.resolve++;
        } else if (roll < w[5]) {
          action = "cancel";
          if (await tryTx(c.connect(signerOf(deal.client)).cancelDeal(id))) done.cancel++;
        } else if (v2 && roll < w[6]) {
          action = "releaseClient";
          if (await tryTx(c.connect(signerOf(deal.freelancer)).releaseClient(id))) done.release++;
        } else if (v2 && roll < w[7]) {
          action = "expire";
          // Pick any open dispute in the contract, let the arbiter's time run out, then settle it.
          const open = [];
          for (let d = 0; d < n; d++)
            (await c.milestonesOf(d)).forEach((m, i) => m.disputed && !m.released && open.push([d, i]));
          const [eid, eidx] = open.length ? open[Math.floor(r() * open.length)] : [id, idx];
          await time.increase(31 * 24 * 3600);
          if (await tryTx(c.connect(pick(everyone)).resolveExpiredDispute(eid, eidx))) done.expire++;
        } else if (v2) {
          action = "withdraw";
          if (await tryTx(c.connect(pick(everyone)).withdraw())) done.withdraw++;
        } else {
          action = "attack: non-party approve/resolve";
          await tryTx(c.connect(pick(everyone)).approveMilestone(id, idx));
          await tryTx(c.connect(pick(everyone)).resolveDispute(id, idx, true));
        }
        await check(step, action);
      }
      // Make sure the run actually exercised the contract, not just reverted all along.
      console.log(`      ${name}: successful actions ${JSON.stringify(done)}`);
      expect(done.create).to.be.greaterThan(5);
      expect(done.approve + done.resolve + done.cancel).to.be.greaterThan(10);
      if (v2) {
        for (const k of ["accept", "approve", "dispute", "resolve", "cancel", "release", "expire", "withdraw"])
          expect(done[k], `V2 action ${k} must succeed at least twice`).to.be.greaterThan(1);
      }
    });
  });
}
