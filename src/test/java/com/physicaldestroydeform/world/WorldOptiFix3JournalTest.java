package com.physicaldestroydeform.world;

import com.physicaldestroydeform.state.SparseStateKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** No Gradle or live World required: filesystem/WAL regression for optiFix3. */
final class WorldOptiFix3JournalTest {
    @TempDir Path temp;
    private static final String DIM="minecraft:overworld";
    private static SparseStateKey key(int x){return new SparseStateKey(DIM,"WORLD",DIM,1,x,64,0,"minecraft:stone");}
    private static WorldReceiptJournal.Receipt receipt(String id,int x){
        return new WorldReceiptJournal.Receipt(id,key(x),WorldReceiptJournal.Status.ABORTED,"TEST",100);
    }
    private static WorldReceiptJournal.Receipt applied(String id,int x){
        return new WorldReceiptJournal.Receipt(id,key(x),WorldReceiptJournal.Status.APPLIED,"TEST",100);
    }
    private static WorldReceiptJournal.State damage(int x,int amount){
        return new WorldReceiptJournal.State(key(x),"0-fingerprint",amount,17,1,100);
    }
    private WorldPersistentJournal open() throws IOException {
        return new WorldPersistentJournal(temp.resolve("current"),temp.resolve("legacy-absent.wal"));
    }
    private static void flush(WorldPersistentJournal j)throws IOException{
        int attempts=0;do{j.checkpointBatch(128);}while(j.checkpointPending()&&++attempts<2000);
        assertFalse(j.checkpointPending());
    }
    private static void deletedEventually(Path file)throws Exception{
        long deadline=System.nanoTime()+10_000_000_000L;
        while(Files.exists(file)&&System.nanoTime()<deadline)Thread.sleep(10);
        assertFalse(Files.exists(file),"retired file should be reclaimed without changing committed manifest");
    }
    @Test void reusedWalChannelRetainsDurableReceiptsAcrossRestart() throws Exception {
        try(var journal=open()){
            journal.enableWalChannelReuse();
            journal.transition(receipt("one",0),null);
            journal.transition(receipt("two",16),null);
            assertTrue(journal.ioPerformance().contains("WAL_FORCE="));
            assertEquals(2,journal.receiptCount());
        }
        try(var restarted=open()){
            assertEquals(receipt("one",0),restarted.receipt("one"));
            assertEquals(receipt("two",16),restarted.receipt("two"));
            assertEquals(2,restarted.receiptCount());
        }
    }
    @Test void walReuseRotatesOnlyAfterManifestIsDurable() throws Exception {
        try(var journal=open()){
            journal.enableWalChannelReuse();
            journal.transition(receipt("first",0),null);
            flush(journal);
            assertEquals(16,journal.bytes());
            journal.transition(receipt("second",16),null);
            flush(journal);
            journal.transition(receipt("third",32),null);
            assertEquals(receipt("first",0),journal.receipt("first"));
        }
        try(var restarted=open()){
            assertEquals(3,restarted.receiptCount());
            assertNotNull(restarted.receipt("first"));
            assertNotNull(restarted.receipt("second"));
            assertNotNull(restarted.receipt("third"));
        }
    }
    @Test void externalWalGrowthFailsClosedAndNeverAppliesNewReceipt()throws Exception {
        try(var journal=open()){
            journal.enableWalChannelReuse();
            journal.transition(receipt("safe",0),null);
            Files.write(temp.resolve("current/recovery-1.wal"),new byte[]{1},StandardOpenOption.APPEND);
            assertThrows(IOException.class,()->journal.transition(receipt("new",0),null));
            assertNull(journal.receipt("new"));
            assertThrows(IOException.class,()->journal.transition(receipt("newer",0),null));
        }
        try(var restarted=open()){
            assertNotNull(restarted.receipt("safe"));
            assertNull(restarted.receipt("new"));
            assertEquals(1,restarted.repairedTailBytes());
        }
    }
    @Test void closedJournalCannotAppendOrCheckpoint()throws Exception {
        var journal=open();journal.enableWalChannelReuse();journal.close();
        assertThrows(IOException.class,()->journal.transition(receipt("closed",0),null));
        assertThrows(IOException.class,()->journal.checkpointBatch(1));
        assertThrows(IOException.class,journal::enableWalChannelReuse);
    }
    @Test void cleanupDeletesRetiredWalButKeepsReferencedOldChunk()throws Exception {
        Path retained;
        try(var journal=open()){
            journal.transition(applied("first",0),damage(0,12));
            flush(journal); // gen2
            try(var files=Files.walk(temp.resolve("current/chunks"))){
                retained=files.filter(p->p.getFileName().toString().endsWith(".2.pds")).findFirst().orElseThrow();
            }
            journal.enableWalChannelReuse();
            journal.transition(receipt("second",32),null);
            flush(journal); // gen3; unchanged gen2 chunk remains live
            deletedEventually(temp.resolve("current/recovery-2.wal"));
            assertTrue(Files.exists(retained),"an unchanged, older generation shard remains referenced");
        }
        try(var reopened=open()){
            assertEquals(2,reopened.receiptCount());
        }
    }
    @Test void startupSweepReclaimsOrphanButNeverTouchesUnrelatedUserFile()throws Exception {
        Path orphan=temp.resolve("current/chunks/0123456789abcdef0123456789abcdef/0_0.1.pds");
        Path unrelated=temp.resolve("current/chunks/user-backup/0_0.1.pds");
        try(var journal=open()){
            journal.transition(receipt("first",0),null);
            flush(journal); // new durable gen2
            Files.createDirectories(orphan.getParent());Files.write(orphan,
                    java.nio.ByteBuffer.allocate(8).putInt(0x50445035).putInt(WorldPersistentJournal.SCHEMA).array());
            Files.createDirectories(unrelated.getParent());Files.write(unrelated,new byte[]{2});
            journal.enableWalChannelReuse();
            deletedEventually(orphan);
            assertTrue(Files.exists(unrelated));
        }
    }
    @Test void migratedSchemaFileIsNeverDeletedByBackgroundCleanup()throws Exception {
        Path historical=temp.resolve("current/chunks/0123456789abcdef0123456789abcdef/0_0.1.pds");
        try(var journal=open()){
            journal.transition(receipt("first",0),null);flush(journal);
            Files.createDirectories(historical.getParent());
            Files.write(historical,java.nio.ByteBuffer.allocate(8)
                    .putInt(0x50445035).putInt(3).array());
            journal.enableWalChannelReuse();
            deletedEventually(temp.resolve("current/recovery-1.wal"));
            assertTrue(Files.exists(historical),"old-format migration evidence is immutable");
        }
    }
    @Test void cleanupPreservesFutureGenerationStagingFiles()throws Exception {
        Path future=temp.resolve("current/chunks/0123456789abcdef0123456789abcdef/0_0.3.pds");
        try(var journal=open()){
            journal.transition(receipt("first",0),null);flush(journal); // gen2
            Files.createDirectories(future.getParent());Files.write(future,new byte[]{7});
            journal.enableWalChannelReuse();
            deletedEventually(temp.resolve("current/recovery-1.wal"));
            assertTrue(Files.exists(future),"a not-yet-committed generation must never be swept");
        }
    }
    @Test void newJournalMetricsSeparateManifestAndSnapshotWithoutChangingSchema()throws Exception {
        try(var journal=open()){
            journal.transition(applied("instrumented",0),damage(0,4));flush(journal);
            String metrics=journal.ioPerformance();
            assertTrue(metrics.contains("MANIFEST_ENCODE="),metrics);
            assertTrue(metrics.contains("MANIFEST_MOVE="),metrics);
            assertTrue(metrics.contains("MANIFEST_FILE_FORCE="),metrics);
            assertTrue(metrics.contains("MANIFEST_DIRECTORY_FORCE="),metrics);
            assertTrue(metrics.contains("SNAPSHOT_BUILD="),metrics);
            assertEquals(WorldPersistentJournal.SCHEMA,6);
        }
    }
}
