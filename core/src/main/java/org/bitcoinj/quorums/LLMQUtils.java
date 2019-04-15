package org.bitcoinj.quorums;

import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.UnsafeByteArrayOutputStream;
import org.bitcoinj.core.Utils;
import org.bitcoinj.crypto.BLSPublicKey;

import java.io.IOException;
import java.util.ArrayList;

public class LLMQUtils {
    static public Sha256Hash buildCommitmentHash(LLMQParameters.LLMQType llmqType, Sha256Hash blockHash, ArrayList<Boolean> validMembers, BLSPublicKey pubKey, Sha256Hash vvecHash)
    {
        try {
            UnsafeByteArrayOutputStream bos = new UnsafeByteArrayOutputStream();
            bos.write(llmqType.getValue());
            bos.write(blockHash.getReversedBytes());
            Utils.booleanArrayListToStream(validMembers, bos);
            pubKey.bitcoinSerialize(bos);
            bos.write(vvecHash.getReversedBytes());
            return Sha256Hash.wrap(Sha256Hash.hashTwice(bos.toByteArray()));
        } catch (IOException x) {
            throw new RuntimeException(x);
        }
    }
}