const { expect } = require("chai");
const { ethers } = require("hardhat");

describe("MilestoneEscrow", function () {
  let escrow, client, freelancer, arbiter, other;
  const M1 = ethers.parseEther("1");
  const M2 = ethers.parseEther("2");

  beforeEach(async function () {
    [client, freelancer, arbiter, other] = await ethers.getSigners();
    const Escrow = await ethers.getContractFactory("MilestoneEscrow");
    escrow = await Escrow.deploy();
  });

  async function createDeal() {
    const tx = await escrow.connect(client).createDeal(freelancer.address, arbiter.address, [M1, M2], {
      value: M1 + M2,
    });
    await tx.wait();
    return 0; // first deal id
  }

  it("creates a deal and locks the full deposit in the contract", async function () {
    const dealId = await createDeal();
    const deal = await escrow.deals(dealId);
    expect(deal.client).to.equal(client.address);
    expect(deal.totalAmount).to.equal(M1 + M2);
    expect(await ethers.provider.getBalance(await escrow.getAddress())).to.equal(M1 + M2);
  });

  it("reverts if the deposit does not match the sum of milestones", async function () {
    await expect(
      escrow.connect(client).createDeal(freelancer.address, arbiter.address, [M1, M2], { value: M1 })
    ).to.be.revertedWith("deposit must equal sum of milestones");
  });

  it("releases a milestone to the freelancer when the client approves it", async function () {
    const dealId = await createDeal();
    const before = await ethers.provider.getBalance(freelancer.address);

    await escrow.connect(client).approveMilestone(dealId, 0);

    const after = await ethers.provider.getBalance(freelancer.address);
    expect(after - before).to.equal(M1);

    const deal = await escrow.deals(dealId);
    expect(deal.releasedAmount).to.equal(M1);
    expect(deal.status).to.equal(0); // still Active - milestone 2 remains
  });

  it("marks the deal Completed once every milestone is released", async function () {
    const dealId = await createDeal();
    await escrow.connect(client).approveMilestone(dealId, 0);
    await escrow.connect(client).approveMilestone(dealId, 1);

    const deal = await escrow.deals(dealId);
    expect(deal.status).to.equal(1); // Completed
  });

  it("only the client can approve a milestone", async function () {
    const dealId = await createDeal();
    await expect(escrow.connect(other).approveMilestone(dealId, 0)).to.be.revertedWith("not the client");
    await expect(escrow.connect(freelancer).approveMilestone(dealId, 0)).to.be.revertedWith("not the client");
  });

  it("freezes a disputed milestone until the arbiter resolves it", async function () {
    const dealId = await createDeal();
    await escrow.connect(freelancer).raiseDispute(dealId, 0);

    await expect(escrow.connect(client).approveMilestone(dealId, 0)).to.be.revertedWith("milestone is disputed");

    // arbiter sides with the freelancer
    const before = await ethers.provider.getBalance(freelancer.address);
    await escrow.connect(arbiter).resolveDispute(dealId, 0, true);
    const after = await ethers.provider.getBalance(freelancer.address);
    expect(after - before).to.equal(M1);
  });

  it("lets the arbiter refund a disputed milestone back to the client instead", async function () {
    const dealId = await createDeal();
    await escrow.connect(client).raiseDispute(dealId, 1);

    const before = await ethers.provider.getBalance(client.address);
    const tx = await escrow.connect(arbiter).resolveDispute(dealId, 1, false);
    await tx.wait();
    const after = await ethers.provider.getBalance(client.address);

    expect(after - before).to.equal(M2);
  });

  it("rejects a non-arbiter trying to resolve a dispute", async function () {
    const dealId = await createDeal();
    await escrow.connect(client).raiseDispute(dealId, 0);
    await expect(escrow.connect(client).resolveDispute(dealId, 0, true)).to.be.revertedWith("not the arbiter");
  });

  it("lets the client cancel and reclaim unreleased milestones", async function () {
    const dealId = await createDeal();
    await escrow.connect(client).approveMilestone(dealId, 0); // release milestone 1 first

    const before = await ethers.provider.getBalance(client.address);
    const tx = await escrow.connect(client).cancelDeal(dealId);
    const receipt = await tx.wait();
    const gasCost = receipt.gasUsed * receipt.gasPrice;
    const after = await ethers.provider.getBalance(client.address);

    // only milestone 2 (M2) should be refunded; milestone 1 was already paid out
    expect(after - before + gasCost).to.equal(M2);

    const deal = await escrow.deals(dealId);
    expect(deal.status).to.equal(2); // Cancelled
  });

  it("blocks cancellation while a milestone is under dispute", async function () {
    const dealId = await createDeal();
    await escrow.connect(client).raiseDispute(dealId, 0);
    await expect(escrow.connect(client).cancelDeal(dealId)).to.be.revertedWith(
      "resolve disputes before cancelling"
    );
  });
});
