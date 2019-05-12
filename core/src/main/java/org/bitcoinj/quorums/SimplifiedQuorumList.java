package org.bitcoinj.quorums;

import com.google.common.base.Preconditions;
import org.bitcoinj.core.*;
import org.bitcoinj.evolution.CoinbaseTx;
import org.bitcoinj.evolution.SimplifiedMasternodeList;
import org.bitcoinj.evolution.SimplifiedMasternodeListDiff;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.utils.Pair;
import org.bitcoinj.utils.Threading;

import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

import static org.bitcoinj.core.Sha256Hash.hashTwice;

public class SimplifiedQuorumList extends Message {

    // critical section to protect the inner data structures
    ReentrantLock lock = Threading.lock("SimplifiedQuorumList");


    private Sha256Hash blockHash;
    private long height;
    HashMap<Pair<Integer, Sha256Hash>, Sha256Hash> minableCommitmentsByQuorum;
    LinkedHashMap<Sha256Hash, FinalCommitment> minableCommitments;


    public SimplifiedQuorumList(NetworkParameters params) {
        super(params);
        blockHash = Sha256Hash.ZERO_HASH;
        height = -1;
        minableCommitmentsByQuorum = new HashMap<Pair<Integer, Sha256Hash>, Sha256Hash>(10);
        minableCommitments = new LinkedHashMap<Sha256Hash, FinalCommitment>(10);
    }

    public SimplifiedQuorumList(NetworkParameters params, byte [] payload, int offset) {
        super(params, payload, offset);
    }

    public SimplifiedQuorumList(SimplifiedQuorumList other) {
        super(other.params);
        this.blockHash = other.blockHash;
        this.height = other.height;
        minableCommitmentsByQuorum = new HashMap<Pair<Integer, Sha256Hash>, Sha256Hash>(other.minableCommitmentsByQuorum);
        minableCommitments = new LinkedHashMap<Sha256Hash, FinalCommitment>(other.minableCommitments);
    }

    @Override
    protected void parse() throws ProtocolException {
        blockHash = readHash();
        height = (int)readUint32();
        int size = (int)readVarInt();
        minableCommitmentsByQuorum = new HashMap<Pair<Integer, Sha256Hash>, Sha256Hash>(size);
        for(int i = 0; i < size; ++i)
        {
            int type = readBytes(1)[0];
            Sha256Hash hash = readHash();
            Sha256Hash hash2 = readHash();
            minableCommitmentsByQuorum.put(new Pair(type, hash), hash2);
        }

        size = (int)readVarInt();
        minableCommitments = new LinkedHashMap<Sha256Hash, FinalCommitment>(size);
        for(long i = 0; i < size; ++i)
        {
            Sha256Hash hash = readHash();
            FinalCommitment commitment = new FinalCommitment(params, payload, cursor);
            cursor += commitment.getMessageSize();
            minableCommitments.put(hash, commitment);
        }
        length = cursor - offset;
    }

    @Override
    protected void bitcoinSerializeToStream(OutputStream stream) throws IOException {
        stream.write(blockHash.getReversedBytes());
        Utils.uint32ToByteStreamLE(height, stream);

        stream.write(new VarInt(minableCommitmentsByQuorum.size()).encode());
        for(Map.Entry<Pair<Integer, Sha256Hash>, Sha256Hash> entry : minableCommitmentsByQuorum.entrySet()) {
            stream.write(entry.getKey().getFirst());
            stream.write(entry.getKey().getSecond().getReversedBytes());
            stream.write(entry.getValue().getReversedBytes());
        }
        stream.write(new VarInt(minableCommitments.size()).encode());
        for(Map.Entry<Sha256Hash, FinalCommitment> entry : minableCommitments.entrySet()) {
            stream.write(entry.getKey().getReversedBytes());
            entry.getValue().bitcoinSerializeToStream(stream);
        }
    }

    public int size() {
        return minableCommitments.size();
    }

    @Override
    public String toString() {
        return "Simplified Quorum List:  count:  " + size();
    }

    public SimplifiedQuorumList applyDiff(SimplifiedMasternodeListDiff diff) {
        lock.lock();
        try {
            CoinbaseTx cbtx = (CoinbaseTx) diff.getCoinBaseTx().getExtraPayloadObject();
            Preconditions.checkArgument(diff.prevBlockHash.equals(blockHash), "The mnlistdiff does not connect to this list.  height: " + height + " vs " + cbtx.getHeight());

            SimplifiedQuorumList result = new SimplifiedQuorumList(this);
            result.blockHash = diff.blockHash;
            result.height = cbtx.getHeight();

            for (Pair<Integer, Sha256Hash> quorum : diff.getDeletedQuorums()) {
                result.removeCommitment(quorum);
            }
            for (FinalCommitment entry : diff.getNewQuorums()) {
                StoredBlock block = Context.get().blockChain.getBlockStore().get(entry.getQuorumHash());
                if(block != null) {
                    LLMQParameters llmqParameters = params.getLlmqs().get(LLMQParameters.LLMQType.fromValue(entry.getLlmqType()));
                    if(llmqParameters == null)
                        throw new ProtocolException("Quorum llmqType is invalid: " + entry.llmqType);
                    int dkgInterval = llmqParameters.dkgInterval;
                    if (block.getHeight() % dkgInterval != 0)
                        throw new ProtocolException("Quorum block height does not match interval for " + entry.quorumHash);
                } else {
                    //TODO:  for some reason llmqType 2 quorumHashs return null on SPV mode
                    //throw new ProtocolException("QuorumHash not found: " + entry.quorumHash);
                }
                result.addCommitment(entry);
            }
            return result;
        } catch (BlockStoreException x) {
            throw new ProtocolException(x);
        } finally {
            lock.unlock();
        }
    }

    void addCommitment(FinalCommitment commitment)
    {
        Sha256Hash commitmentHash = commitment.getHash();

        lock.lock();
        try {
            Pair<Integer, Sha256Hash> pair = new Pair(commitment.llmqType, commitment.quorumHash);
            minableCommitmentsByQuorum.put(pair, commitmentHash);
            minableCommitments.put(commitmentHash, commitment);
        } finally {
            lock.unlock();
        }

    }

    void removeCommitment(Pair<Integer, Sha256Hash> quorum)
    {
        lock.lock();
        try {
            if (minableCommitmentsByQuorum.containsKey(quorum)) {
                Sha256Hash commitmentHash = minableCommitmentsByQuorum.get(quorum);
                minableCommitments.remove(commitmentHash);
                minableCommitmentsByQuorum.remove(quorum);
            }
        } finally {
            lock.unlock();
        }
    }

    public FinalCommitment getCommitment(Sha256Hash commitmentHash)
    {
        lock.lock();
        try {
            FinalCommitment p = minableCommitments.get(commitmentHash);
            if (p == null) {
                return null;
            }
            return p;
        } finally {
            lock.unlock();
        }
    }


    public boolean verify(Transaction coinbaseTx, SimplifiedMasternodeList mnList) throws VerificationException {
        lock.lock();

        try {

            if (!(coinbaseTx.getExtraPayloadObject() instanceof CoinbaseTx))
                throw new VerificationException("transaction is not a coinbase transaction");

            CoinbaseTx cbtx = (CoinbaseTx) coinbaseTx.getExtraPayloadObject();

            ArrayList<Sha256Hash> commitmentHashes = new ArrayList<Sha256Hash>();

            for (FinalCommitment commitment : minableCommitments.values()) {
                commitmentHashes.add(commitment.getHash());
            }

            Collections.sort(commitmentHashes, new Comparator<Sha256Hash>() {
                @Override
                public int compare(Sha256Hash o1, Sha256Hash o2) {
                    return o1.compareTo(o2);
                }
            });

            if (!cbtx.getMerkleRootQuorums().equals(calculateMerkleRoot(commitmentHashes)))
                throw new VerificationException("MerkleRoot of quorum list does not match coinbaseTx - " + commitmentHashes.size());

            return true;
        } finally {
            lock.unlock();
        }
    }

    public Sha256Hash calculateMerkleRoot() {
        lock.lock();
        try {
            ArrayList<Sha256Hash> commitmentHashes = new ArrayList<Sha256Hash>();

            for(FinalCommitment commitment : minableCommitments.values()) {
                commitmentHashes.add(commitment.getHash());
            }

            Collections.sort(commitmentHashes, new Comparator<Sha256Hash>() {
                @Override
                public int compare(Sha256Hash o1, Sha256Hash o2) {
                    return o1.compareTo(o2);
                }
            });

            return calculateMerkleRoot(commitmentHashes);
        } finally {
            lock.unlock();
        }
    }

    private Sha256Hash calculateMerkleRoot(List<Sha256Hash> hashes) {
        List<byte[]> tree = buildMerkleTree(hashes);
        return Sha256Hash.wrap(tree.get(tree.size() - 1));
    }

    private List<byte[]> buildMerkleTree(List<Sha256Hash> hashes) {
        // The Merkle root is based on a tree of hashes calculated from the masternode list proRegHash:
        //
        //     root
        //      / \
        //   A      B
        //  / \    / \
        // t1 t2 t3 t4
        //
        // The tree is represented as a list: t1,t2,t3,t4,A,B,root where each
        // entry is a hash.
        //
        // The hashing algorithm is double SHA-256. The leaves are a hash of the serialized contents of the transaction.
        // The interior nodes are hashes of the concenation of the two child hashes.
        //
        // This structure allows the creation of proof that a transaction was included into a block without having to
        // provide the full block contents. Instead, you can provide only a Merkle branch. For example to prove tx2 was
        // in a block you can just provide tx2, the hash(tx1) and B. Now the other party has everything they need to
        // derive the root, which can be checked against the block header. These proofs aren't used right now but
        // will be helpful later when we want to download partial block contents.
        //
        // Note that if the number of transactions is not even the last tx is repeated to make it so (see
        // tx3 above). A tree with 5 transactions would look like this:
        //
        //         root
        //        /     \
        //       1        5
        //     /   \     / \
        //    2     3    4  4
        //  / \   / \   / \
        // t1 t2 t3 t4 t5 t5
        ArrayList<byte[]> tree = new ArrayList<byte[]>();
        // Start by adding all the hashes of the transactions as leaves of the tree.
        for (Sha256Hash hash : hashes) {
            tree.add(hash.getBytes());
        }
        int levelOffset = 0; // Offset in the list where the currently processed level starts.
        // Step through each level, stopping when we reach the root (levelSize == 1).
        for (int levelSize = hashes.size(); levelSize > 1; levelSize = (levelSize + 1) / 2) {
            // For each pair of nodes on that level:
            for (int left = 0; left < levelSize; left += 2) {
                // The right hand node can be the same as the left hand, in the case where we don't have enough
                // transactions.
                int right = Math.min(left + 1, levelSize - 1);
                byte[] leftBytes = Utils.reverseBytes(tree.get(levelOffset + left));
                byte[] rightBytes = Utils.reverseBytes(tree.get(levelOffset + right));
                tree.add(Utils.reverseBytes(hashTwice(leftBytes, 0, 32, rightBytes, 0, 32)));
            }
            // Move to the next level.
            levelOffset += levelSize;
        }
        return tree;
    }

    public interface ForeachQuorumCallback {
        void processQuorum(FinalCommitment finalCommitment);
    }

    public void forEachQuorum(boolean onlyValid, ForeachQuorumCallback callback) {
        for(Map.Entry<Sha256Hash, FinalCommitment> entry : minableCommitments.entrySet()) {
            if(!onlyValid || isCommitmentValid(entry.getValue())) {
                callback.processQuorum(entry.getValue());
            }
        }
    }

    public int getCount()
    {
        return minableCommitments.size();
    }

    public int getValidCount()
    {
        int count = 0;
        for (Map.Entry<Sha256Hash, FinalCommitment> p : minableCommitments.entrySet()) {
            if (isCommitmentValid(p.getValue())) {
                count++;
            }
        }
        return count;
    }

    public boolean isCommitmentValid(FinalCommitment entry) {
        return !entry.isNull();
    }

    public Sha256Hash getBlockHash() {
        return blockHash;
    }

    public long getHeight() {
        return height;
    }
}