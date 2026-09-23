package com.escrow.platform.service;

import com.escrow.platform.config.Web3Properties;
import org.springframework.stereotype.Service;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;

import java.math.BigInteger;
import java.util.List;

/**
 * Same approach as the portfolio tracker's ChainReaderService: hand-encoded
 * ABI calls against MilestoneEscrow's compiler-generated struct getters
 * (`deals(uint256)`, `milestones(uint256,uint256)`), which return plain
 * scalars because neither struct contains a dynamic field.
 */
@Service
public class ChainReaderService {

    private final Web3j web3j;
    private final Web3Properties props;

    public ChainReaderService(Web3j web3j, Web3Properties props) {
        this.web3j = web3j;
        this.props = props;
    }

    public DealInfo getDeal(long dealId) {
        Function function = new Function(
                "deals",
                List.of(new Uint256(BigInteger.valueOf(dealId))),
                List.of(
                        new TypeReference<Address>() {},  // client
                        new TypeReference<Address>() {},  // freelancer
                        new TypeReference<Address>() {},  // arbiter
                        new TypeReference<Uint256>() {},  // totalAmount
                        new TypeReference<Uint256>() {},  // releasedAmount
                        new TypeReference<Uint8>() {},    // status (enum)
                        new TypeReference<Uint256>() {}   // createdAt
                )
        );

        List<Type> d = call(props.getEscrowAddress(), function);
        return new DealInfo(
                dealId,
                (String) d.get(0).getValue(),
                (String) d.get(1).getValue(),
                (String) d.get(2).getValue(),
                (BigInteger) d.get(3).getValue(),
                (BigInteger) d.get(4).getValue(),
                ((BigInteger) d.get(5).getValue()).intValue(),
                (BigInteger) d.get(6).getValue()
        );
    }

    public int getMilestoneCount(long dealId) {
        Function function = new Function(
                "milestoneCount",
                List.of(new Uint256(BigInteger.valueOf(dealId))),
                List.of(new TypeReference<Uint256>() {})
        );
        List<Type> d = call(props.getEscrowAddress(), function);
        return ((BigInteger) d.get(0).getValue()).intValueExact();
    }

    public MilestoneInfo getMilestone(long dealId, int index) {
        Function function = new Function(
                "milestones",
                List.of(new Uint256(BigInteger.valueOf(dealId)), new Uint256(BigInteger.valueOf(index))),
                List.of(
                        new TypeReference<Uint256>() {}, // amount
                        new TypeReference<Bool>() {},    // released
                        new TypeReference<Bool>() {}     // disputed
                )
        );
        List<Type> d = call(props.getEscrowAddress(), function);
        return new MilestoneInfo(index, (BigInteger) d.get(0).getValue(), (Boolean) d.get(1).getValue(), (Boolean) d.get(2).getValue());
    }

    private List<Type> call(String contractAddress, Function function) {
        String encoded = FunctionEncoder.encode(function);
        try {
            EthCall response = web3j.ethCall(
                    Transaction.createEthCallTransaction(null, contractAddress, encoded),
                    DefaultBlockParameterName.LATEST
            ).send();

            if (response.hasError()) {
                throw new ChainReadException("eth_call failed: " + response.getError().getMessage());
            }
            return FunctionReturnDecoder.decode(response.getValue(), function.getOutputParameters());
        } catch (Exception e) {
            throw new ChainReadException("Failed to read " + function.getName() + " from " + contractAddress, e);
        }
    }

    public record DealInfo(long dealId, String client, String freelancer, String arbiter, BigInteger totalAmount,
                            BigInteger releasedAmount, int status, BigInteger createdAt) {
    }

    public record MilestoneInfo(int index, BigInteger amount, boolean released, boolean disputed) {
    }

    public static class ChainReadException extends RuntimeException {
        public ChainReadException(String message) {
            super(message);
        }

        public ChainReadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
