package com.physicaldestroydeform.world;

import com.physicaldestroydeform.state.SparseStateKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Instrumentation has no role in crash-recovery or existing receipt identity rules. */
final class WorldJournalIoInstrumentationTest {
    @TempDir Path temp;
    private static final SparseStateKey KEY=new SparseStateKey("minecraft:overworld","WORLD","minecraft:overworld",1,2,64,3,"minecraft:stone");
    private static WorldReceiptJournal.Receipt receipt(String id){
        return new WorldReceiptJournal.Receipt(id,KEY,WorldReceiptJournal.Status.ABORTED,"optiFix2-io-audit",100);
    }
    private WorldPersistentJournal open() throws IOException {
        return new WorldPersistentJournal(temp.resolve("current"),temp.resolve("absent.wal"),0);
    }
    private static void checkpoint(WorldPersistentJournal j) throws IOException {
        do{j.checkpointBatch(128);}while(j.checkpointPending());
    }
    @Test void durableAppendReportsTimeWithoutChangingReceipt() throws Exception {
        var j=open();j.transition(receipt("id-a"),null);
        assertEquals(receipt("id-a"),j.receipt("id-a"));
        String metrics=j.ioPerformance();
        assertTrue(metrics.contains("WAL_APPEND="),metrics);
        assertTrue(metrics.contains("WAL_FORCE="),metrics);
    }
    @Test void checkpointReportsWriteAndPublishWithoutChangingWal() throws Exception {
        var j=open();j.transition(receipt("id-b"),null);checkpoint(j);
        String metrics=j.ioPerformance();
        assertTrue(metrics.contains("RECEIPT_ENCODE="),metrics);
        assertTrue(metrics.contains("RECEIPT_INDEX="),metrics);
        assertTrue(metrics.contains("DURABLE_WRITE="),metrics);
        assertTrue(metrics.contains("PUBLISH="),metrics);
        assertTrue(metrics.contains("CHECKPOINT="),metrics);
        assertEquals(16,j.bytes());
    }
    @Test void coldLookupRemainsExactAndReportsDiskDuration() throws Exception {
        var j=open();j.transition(receipt("id-c"),null);checkpoint(j);
        var restored=open();assertEquals(receipt("id-c"),restored.receipt("id-c"));
        assertTrue(restored.ioPerformance().contains("COLD_RECEIPT_READ="),restored.ioPerformance());
        assertNull(restored.receipt("absent"));
    }
    @Test void journalReopenRetainsDurableReceiptAndDoesNotCarryOldTimings() throws Exception {
        var j=open();j.transition(receipt("id-d"),null);checkpoint(j);
        var restored=open();
        assertFalse(restored.ioPerformance().contains("WAL_APPEND="));
        assertEquals(receipt("id-d"),restored.receipt("id-d"));
    }
}
