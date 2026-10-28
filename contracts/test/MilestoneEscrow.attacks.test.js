// Attack tests for security/THREAT_MODEL.md. Each finding is run against the ORIGINAL contract
// (MilestoneEscrow, deployed on Sepolia) and the fixed one (MilestoneEscrowV2).
// "succeeds on the original" tests document a real weakness; they pass when the attack WORKS.
const { expect } = require("chai");
const { ethers, network } = require("hardhat");
const { time } = require("@nomicfoundation/hardhat-network-helpers");

const E = (x) => ethers.parseEther(String(x));
const M1 = E(1);
const M2 = E(2);

async function deploy(name) {
  const c = await (await ethers.getContractFactory(name)).deploy();
  return c;
}

const iface = new ethers.Interface([
  "function cancelDeal(uint256)",
  "function raiseDispute(uint256,uint256)",
  "function withdraw()",
  "function resolveDispute(uint256,uint256,bool)",
]);

describe("Attacks: original MilestoneEscrow vs MilestoneEscrowV2", function () {
  let client, freelancer, arbiter, mallory;

  beforeEach(async function () {
    [client, freelancer, arbiter, mallory] = await ethers.getSigners();
  });

  // ------------------------------------------------------------------------------------------
  describe("E1: client cancels after the work is done", function () {
    it("succeeds on the original: freelancer delivered milestone 1, client cancels and takes everything back", async function () {
      const v1 = await deploy("MilestoneEscrow");
      await v1.connect(client).createDeal(freelancer.address, arbiter.address, [M1, M2], { value: M1 + M2 });
      // ...freelancer works on milestone 1 and delivers it off-chain; client never approves:
      const before = await ethers.provider.getBalance(freelancer.address);
      await expect(v1.connect(client).cancelDeal(0)).to.emit(v1, "DealCancelled").withArgs(0, M1 + M2);
      expect(await ethers.provider.getBalance(freelancer.address)).to.equal(before); // freelancer got nothing
      expect(await ethers.provider.getBalance(await v1.getAddress())).to.equal(0n);
    });

    it("succeeds on the original even when the freelancer tries to dispute: client front-runs it", async function () {
      const v1 = await deploy("MilestoneEscrow");
      await v1.connect(client).createDeal(freelancer.address, arbiter.address, [M1, M2], { value: M1 + M2 });
      // Freelancer sends raiseDispute; client sees it in the mempool and sends cancelDeal with a
      // higher tip. Both land in the same block, the client's first.
      await network.provider.send("evm_setAutomine", [false]);
      try {
        const disputeTx = await v1.connect(freelancer).raiseDispute(0, 0, {
          maxPriorityFeePerGas: ethers.parseUnits("1", "gwei"), gasLimit: 200_000,
        });
        const cancelTx = await v1.connect(client).cancelDeal(0, {
          maxPriorityFeePerGas: ethers.parseUnits("50", "gwei"), gasLimit: 200_000,
        });
        await network.provider.send("evm_mine");
        const rc = await ethers.provider.getTransactionReceipt(cancelTx.hash);
        const rd = await ethers.provider.getTransactionReceipt(disputeTx.hash);
        expect(rc.status).to.equal(1); // cancel went through
        expect(rd.status).to.equal(0); // dispute reverted: "deal not active"
        expect(rc.index).to.be.lessThan(rd.index); // the higher tip was ordered first
      } finally {
        await network.provider.send("evm_setAutomine", [true]);
      }
    });

    it("fails on V2: once the freelancer accepts, the client can't cancel; money moves only by approval or the arbiter", async function () {
      const v2 = await deploy("MilestoneEscrowV2");
      await v2.connect(client).createDeal(freelancer.address, arbiter.address, [M1, M2], { value: M1 + M2 });
      await v2.connect(freelancer).acceptDeal(0);
      await expect(v2.connect(client).cancelDeal(0)).to.be.revertedWith("deal already accepted: use a dispute");
      // The client's remaining route is a dispute, which the arbiter decides:
      await v2.connect(client).raiseDispute(0, 0);
      await v2.connect(arbiter).resolveDispute(0, 0, true);
      expect(await v2.owed(freelancer.address)).to.equal(M1);
    });

    it("V2: before acceptance the client can still cancel (nothing has been agreed yet)", async function () {
      const v2 = await deploy("MilestoneEscrowV2");
      await v2.connect(client).createDeal(freelancer.address, arbiter.address, [M1, M2], { value: M1 + M2 });
      await v2.connect(client).cancelDeal(0);
      expect(await v2.owed(client.address)).to.equal(M1 + M2);
    });

    it("V2: after acceptance the freelancer can release the client (mutual exit)", async function () {
      const v2 = await deploy("MilestoneEscrowV2");
      await v2.connect(client).createDeal(freelancer.address, arbiter.address, [M1, M2], { value: M1 + M2 });
      await v2.connect(freelancer).acceptDeal(0);
      await v2.connect(client).approveMilestone(0, 0);
      await expect(v2.connect(client).releaseClient(0)).to.be.revertedWith("not the freelancer");
      await v2.connect(freelancer).releaseClient(0);
      expect(await v2.owed(client.address)).to.equal(M2);
      expect(await v2.owed(freelancer.address)).to.equal(M1);
    });
  });

  // ------------------------------------------------------------------------------------------
  describe("E2: client appoints itself (or a friend) as arbiter", function () {
    it("succeeds on the original: client is also the arbiter and rules every dispute for itself", async function () {
      const v1 = await deploy("MilestoneEscrow");
      await v1.connect(client).createDeal(freelancer.address, client.address, [M1], { value: M1 });
      await v1.connect(freelancer).raiseDispute(0, 0); // freelancer asks for a fair hearing...
      const before = await ethers.provider.getBalance(client.address);
      const tx = await v1.connect(client).resolveDispute(0, 0, false); // ...and the "arbiter" is the client
      const r = await tx.wait();
      const gas = r.gasUsed * r.gasPrice;
      expect((await ethers.provider.getBalance(client.address)) - before + gas).to.equal(M1);
    });

    it("succeeds on the original: the freelancer can also be named arbiter (no checks at all)", async function () {
      const v1 = await deploy("MilestoneEscrow");
      await expect(v1.connect(client).createDeal(freelancer.address, freelancer.address, [M1], { value: M1 })).to.not.be.reverted;
    });

    it("fails on V2: arbiter can be neither party, and the freelancer must accept the arbiter", async function () {
      const v2 = await deploy("MilestoneEscrowV2");
      await expect(v2.connect(client).createDeal(freelancer.address, client.address, [M1], { value: M1 })).to.be.revertedWith("invalid arbiter");
      await expect(v2.connect(client).createDeal(freelancer.address, freelancer.address, [M1], { value: M1 })).to.be.revertedWith("invalid arbiter");
      // A colluding third party (mallory) can be proposed, but nothing binds until the freelancer accepts:
      await v2.connect(client).createDeal(freelancer.address, mallory.address, [M1], { value: M1 });
      await expect(v2.connect(client).raiseDispute(0, 0)).to.be.revertedWith("deal not active");
      await expect(v2.connect(mallory).resolveDispute(0, 0, false)).to.be.revertedWith("deal not active");
    });
  });

  // ------------------------------------------------------------------------------------------
  describe("E3: dispute with an absent arbiter locks the deal forever", function () {
    it("succeeds on the original: one disputed milestone + silent arbiter locks ALL unreleased funds, a year later too", async function () {
      const v1 = await deploy("MilestoneEscrow");
      await v1.connect(client).createDeal(freelancer.address, arbiter.address, [M1, M2], { value: M1 + M2 });
      await v1.connect(freelancer).raiseDispute(0, 0); // dispute only milestone 1
      await time.increase(365 * 24 * 3600);
      await expect(v1.connect(client).cancelDeal(0)).to.be.revertedWith("resolve disputes before cancelling");
      // Milestone 2 is undisputed, but the client can't get it back either; only approving it works.
      expect(await ethers.provider.getBalance(await v1.getAddress())).to.equal(M1 + M2);
    });

    it("fails on V2: after DISPUTE_TIMEOUT anyone can split the milestone 50/50", async function () {
      const v2 = await deploy("MilestoneEscrowV2");
      const odd = E(1) + 1n; // odd wei goes to the client
      await v2.connect(client).createDeal(freelancer.address, arbiter.address, [odd], { value: odd });
      await v2.connect(freelancer).acceptDeal(0);
      await v2.connect(freelancer).raiseDispute(0, 0);
      await expect(v2.connect(mallory).resolveExpiredDispute(0, 0)).to.be.revertedWith("arbiter still has time");
      await time.increase(30 * 24 * 3600);
      await v2.connect(mallory).resolveExpiredDispute(0, 0);
      expect(await v2.owed(freelancer.address)).to.equal(odd / 2n);
      expect(await v2.owed(client.address)).to.equal(odd - odd / 2n);
      expect((await v2.deals(0)).status).to.equal(3); // Completed
    });
  });

  // ------------------------------------------------------------------------------------------
  describe("E4: a recipient that refuses ETH", function () {
    it("succeeds on the original: client contract rejects ETH, so the arbiter can only rule for the freelancer", async function () {
      const v1 = await deploy("MilestoneEscrow");
      const cc = await (await ethers.getContractFactory("ContractClient")).deploy(await v1.getAddress());
      await cc.createDeal(freelancer.address, arbiter.address, [M1], { value: M1 });
      await cc.setMode(1, "0x"); // RejectEth
      await cc.act(iface.encodeFunctionData("raiseDispute", [0, 0]));
      await expect(v1.connect(arbiter).resolveDispute(0, 0, false)).to.be.revertedWith("payment transfer failed");
      // the arbiter's real decision ("refund the client") is impossible; only the other outcome works
      await expect(v1.connect(arbiter).resolveDispute(0, 0, true)).to.not.be.reverted;
    });

    it("fails on V2: the ruling is recorded as a credit; whether the client can receive ETH doesn't matter", async function () {
      const v2 = await deploy("MilestoneEscrowV2");
      const cc = await (await ethers.getContractFactory("ContractClient")).deploy(await v2.getAddress());
      await cc.createDeal(freelancer.address, arbiter.address, [M1], { value: M1 });
      await v2.connect(freelancer).acceptDeal(0);
      await cc.setMode(1, "0x");
      await cc.act(iface.encodeFunctionData("raiseDispute", [0, 0]));
      await v2.connect(arbiter).resolveDispute(0, 0, false);
      expect(await v2.owed(await cc.getAddress())).to.equal(M1);
      // its own withdrawal fails (its choice), and nobody else is affected:
      await expect(cc.act(iface.encodeFunctionData("withdraw"))).to.be.revertedWith("withdraw failed");
      expect(await v2.owed(await cc.getAddress())).to.equal(M1); // still owed; can retry after fixing itself
    });
  });

  // ------------------------------------------------------------------------------------------
  describe("E5: reentrancy while being paid (defence holds in both)", function () {
    it("original: a client contract re-entering cancelDeal during its refund is stopped; refunded exactly once", async function () {
      const v1 = await deploy("MilestoneEscrow");
      const cc = await (await ethers.getContractFactory("ContractClient")).deploy(await v1.getAddress());
      await cc.createDeal(freelancer.address, arbiter.address, [M1, M2], { value: M1 + M2 });
      await cc.setMode(2, iface.encodeFunctionData("cancelDeal", [0])); // Reenter
      await cc.act(iface.encodeFunctionData("cancelDeal", [0]));
      expect(await cc.reentryAttempts()).to.equal(1n);
      expect(await cc.reentrySuccesses()).to.equal(0n); // ReentrancyGuard (and status=Cancelled) stopped it
      expect(await ethers.provider.getBalance(await cc.getAddress())).to.equal(M1 + M2);
      expect(await ethers.provider.getBalance(await v1.getAddress())).to.equal(0n);
    });

    it("original (Slither's reentrancy-eth): re-entering during the LAST milestone's payment can't touch the deal", async function () {
      const v1 = await deploy("MilestoneEscrow");
      const cc = await (await ethers.getContractFactory("ContractClient")).deploy(await v1.getAddress());
      await cc.createDeal(freelancer.address, arbiter.address, [M1], { value: M1 });
      await cc.act(iface.encodeFunctionData("raiseDispute", [0, 0]));
      // While the refund is paid the deal is still "Active" (status is set after the call), so try
      // the unguarded raiseDispute again:
      await cc.setMode(2, iface.encodeFunctionData("raiseDispute", [0, 0]));
      await v1.connect(arbiter).resolveDispute(0, 0, false);
      expect(await cc.reentryAttempts()).to.equal(1n);
      expect(await cc.reentrySuccesses()).to.equal(0n); // "already released": the milestone was settled first
      expect((await v1.deals(0)).status).to.equal(1); // Completed
    });

    it("V2: re-entering withdraw() pays once", async function () {
      const v2 = await deploy("MilestoneEscrowV2");
      const cc = await (await ethers.getContractFactory("ContractClient")).deploy(await v2.getAddress());
      await cc.createDeal(freelancer.address, arbiter.address, [M1, M2], { value: M1 + M2 });
      await cc.act(iface.encodeFunctionData("cancelDeal", [0])); // before acceptance: allowed, credits M1+M2
      await cc.setMode(2, iface.encodeFunctionData("withdraw"));
      await cc.act(iface.encodeFunctionData("withdraw"));
      expect(await cc.reentrySuccesses()).to.equal(0n);
      expect(await ethers.provider.getBalance(await cc.getAddress())).to.equal(M1 + M2);
      expect(await v2.owed(await cc.getAddress())).to.equal(0n);
      expect(await ethers.provider.getBalance(await v2.getAddress())).to.equal(0n);
    });
  });

  // ------------------------------------------------------------------------------------------
  describe("E6: deal IDs that were never created", function () {
    it("original: an unknown deal ID reads as Active (status 0) - harmless today, a trap for future code", async function () {
      const v1 = await deploy("MilestoneEscrow");
      expect((await v1.deals(42)).status).to.equal(0); // DealStatus.Active
    });

    it("V2: an unknown deal ID is None and every action on it reverts", async function () {
      const v2 = await deploy("MilestoneEscrowV2");
      expect((await v2.deals(42)).status).to.equal(0); // DealStatus.None
      await expect(v2.connect(client).raiseDispute(42, 0)).to.be.revertedWith("deal not active");
      await expect(v2.connect(mallory).resolveExpiredDispute(42, 0)).to.be.revertedWith("deal not active");
    });
  });
});
