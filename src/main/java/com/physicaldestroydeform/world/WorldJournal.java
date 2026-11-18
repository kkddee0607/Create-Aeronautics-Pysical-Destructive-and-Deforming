package com.physicaldestroydeform.world;

import com.physicaldestroydeform.state.SparseStateKey;
import java.io.IOException;
import java.util.List;

/** World storage contract. Receipts and state must become durable in one transition. */
public interface WorldJournal {
    WorldReceiptJournal.State get(SparseStateKey key);
    WorldReceiptJournal.Receipt receipt(String applyId);
    int size();
    int receiptCount();
    long bytes();
    List<WorldReceiptJournal.Receipt> uncertain();
    List<WorldReceiptJournal.State> stateSnapshot();
    boolean hasPosition(String dimension,int x,int y,int z);
    void transition(WorldReceiptJournal.Receipt receipt,WorldReceiptJournal.State state) throws IOException;
    /** Legacy test/migration WAL intentionally cannot replay payloads. */
    default void enqueue(WorldCommitCore.Intent intent,com.physicaldestroydeform.DamageModel.Settings settings,
                         long expiresGameTick) throws IOException {
        transition(new WorldReceiptJournal.Receipt(intent.applyId(),intent.key(),
                WorldReceiptJournal.Status.DEFERRED,"QUEUED_IN_CURRENT_RUN",intent.eventTick()),null);
    }
    void discardAtPosition(String dimension,int x,int y,int z) throws IOException;
    void discardStale(SparseStateKey key) throws IOException;
    default boolean recoveredUncertain(String applyId) { return false; }
    default boolean canStore(SparseStateKey key) { return true; }
    /** Non-null means this store cannot safely fsync another new ApplyId. */
    default String admissionRejectReason() { return null; }
    default long revision(String dim,int x,int y,int z) { return 0; }
    default long replaceBlock(String dim,int x,int y,int z) throws IOException {
        discardAtPosition(dim,x,y,z);
        return revision(dim,x,y,z)+1;
    }
    default String storageStatus() { return "LEGACY_WAL"; }
    /** Explicit deadline/settings handoff requires a replayable payload, not a legacy marker. */
    default boolean supportsPendingPayload() { return false; }
    default int checkpointBatch(int maxShards) throws IOException { return 0; }
    default boolean checkpointPending() { return false; }
}
