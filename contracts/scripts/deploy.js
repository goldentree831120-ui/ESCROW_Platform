const { ethers } = require("hardhat");

async function main() {
  const [deployer] = await ethers.getSigners();
  console.log("Deploying with account:", deployer.address);

  const Escrow = await ethers.getContractFactory("MilestoneEscrow");
  const escrow = await Escrow.deploy();
  await escrow.waitForDeployment();

  console.log("MilestoneEscrow deployed to:", await escrow.getAddress());
  console.log("\nCopy this into backend/src/main/resources/application.yml:");
  console.log("escrow-address:", await escrow.getAddress());
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
