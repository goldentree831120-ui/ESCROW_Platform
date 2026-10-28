// SPDX-License-Identifier: MIT
pragma solidity 0.8.24;

/// TEST ONLY. A deal client that is itself a contract, to test two attacks against both escrow
/// versions (they share the createDeal / raiseDispute / cancelDeal ABI):
///   Mode.RejectEth  receive() reverts: can a recipient that refuses ETH block settlement? [E4]
///   Mode.Reenter    receive() calls back into the escrow while being paid: can it take more? [E5]
contract ContractClient {
    enum Mode {
        Normal,
        RejectEth,
        Reenter
    }

    Mode public mode;
    address public escrow;
    bytes public reentryCall; // the call receive() tries while being paid
    uint256 public reentryAttempts;
    uint256 public reentrySuccesses;
    bytes public lastReentryError;

    constructor(address escrow_) {
        escrow = escrow_;
    }

    function setMode(Mode m, bytes calldata call_) external {
        mode = m;
        reentryCall = call_;
    }

    function createDeal(address freelancer, address arbiter, uint256[] calldata amounts) external payable returns (uint256 id) {
        (bool ok, bytes memory ret) = escrow.call{value: msg.value}(
            abi.encodeWithSignature("createDeal(address,address,uint256[])", freelancer, arbiter, amounts)
        );
        _bubble(ok, ret);
        id = abi.decode(ret, (uint256));
    }

    /// Any call to the escrow as this contract (raiseDispute, cancelDeal, withdraw, ...).
    function act(bytes calldata data) external {
        (bool ok, bytes memory ret) = escrow.call(data);
        _bubble(ok, ret);
    }

    receive() external payable {
        if (mode == Mode.RejectEth) revert("ContractClient: I refuse ETH");
        if (mode == Mode.Reenter) {
            reentryAttempts++;
            (bool ok, bytes memory ret) = escrow.call(reentryCall); // swallow the failure, record it
            if (ok) reentrySuccesses++;
            else lastReentryError = ret;
        }
    }

    function _bubble(bool ok, bytes memory ret) private pure {
        if (!ok) {
            assembly {
                revert(add(ret, 32), mload(ret))
            }
        }
    }
}
