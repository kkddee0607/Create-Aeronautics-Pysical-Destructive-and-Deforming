package com.physicaldestroydeform.world;

import com.physicaldestroydeform.state.SparseStateKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import static com.physicaldestroydeform.world.WorldReceiptJournal.Status.*;
import static org.junit.jupiter.api.Assertions.*;

/** Cache eviction must never be an ApplyId eviction. All disk fixtures use the production writer. */
final class WorldReceiptColdCacheTest {
    @TempDir Path temp;
    private static final SparseStateKey KEY=new SparseStateKey("minecraft:overworld","WORLD","minecraft:overworld",1,1,64,2,"minecraft:stone");
    private WorldPersistentJournal open() throws IOException {
        return new WorldPersistentJournal(temp.resolve("current"),temp.resolve("absent.wal"),1024);
    }
    private static WorldReceiptJournal.Receipt r(String id,WorldReceiptJournal.Status status){
        return new WorldReceiptJournal.Receipt(id,KEY,status,"receipt-cache-test",100);
    }
    private static void flush(WorldPersistentJournal j)throws IOException{
        do{j.checkpointBatch(128);}while(j.checkpointPending());
    }
    private static List<String> sameBucket(int n)throws Exception{
        List<String> ids=new ArrayList<>();
        var hash=MessageDigest.getInstance("SHA-256");
        for(int i=0;ids.size()<n;i++){
            String id="candidate-"+i;
            if(hash.digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8))[0]==0)ids.add(id);
        }
        return ids;
    }
    @Test void terminalRecordsAreColdAfterCheckpointAndStartup()throws Exception{
        var j=open();for(String id:sameBucket(8))j.transition(r(id,APPLIED),null);
        flush(j);assertEquals(8,j.receiptCount());assertEquals(0,j.receiptMemoryStats().dirtyReceipts());
        var reopened=open();var s=reopened.receiptMemoryStats();
        assertEquals(8,s.indexedReceipts());assertEquals(0,s.cachedReceipts());assertEquals(0,s.uncertainReceipts());
    }
    @Test void evictionRetainsExactTerminalDuplicateProtection()throws Exception{
        var ids=sameBucket(10);var j=open();for(String id:ids)j.transition(r(id,ABORTED),null);flush(j);
        var reopened=open();for(String id:ids)assertEquals(r(id,ABORTED),reopened.receipt(id));
        assertTrue(reopened.receiptMemoryStats().cacheBytes()<=1024);
        assertEquals(10,reopened.receiptCount());
        assertThrows(IllegalArgumentException.class,()->reopened.transition(r(ids.getFirst(),APPLIED),null));
    }
    @Test void differentIdsWithinSameBucketNeverAlias()throws Exception{
        var ids=sameBucket(4);var j=open();j.transition(r(ids.get(0),ABORTED),null);j.transition(r(ids.get(2),APPLIED),null);flush(j);
        j=open();assertEquals(r(ids.get(0),ABORTED),j.receipt(ids.get(0)));
        assertEquals(r(ids.get(2),APPLIED),j.receipt(ids.get(2)));
        assertNull(j.receipt(ids.get(1)));assertNull(j.receipt(ids.get(3)));
    }
    @Test void activeReceiptOverridesOlderCheckpointUntilPublished()throws Exception{
        var j=open();j.transition(r("pending",DEFERRED),null);flush(j);
        j=open();j.transition(r("pending",APPLIED),null);
        assertEquals(r("pending",APPLIED),j.receipt("pending"));assertTrue(j.uncertain().isEmpty());
        assertEquals(r("pending",APPLIED),open().receipt("pending"));flush(j);
        assertEquals(r("pending",APPLIED),open().receipt("pending"));assertEquals(1,j.receiptCount());
    }
    @Test void preparedAndQuarantineIndexesRemainAvailableAfterColdEviction()throws Exception{
        var j=open();j.transition(r("prepared",PREPARED),null);j.transition(r("quarantine",PARTIAL_QUARANTINED),null);
        for(String id:sameBucket(5))j.transition(r(id,ABORTED),null);flush(j);
        j=open();assertEquals(List.of(r("prepared",PREPARED)),j.uncertain());
        assertEquals(List.of(r("quarantine",PARTIAL_QUARANTINED)),j.quarantinedSnapshot());
        assertTrue(j.recoveredUncertain("prepared"));
    }
    @Test void uncommittedStageLeavesOriginalWalRecoverable()throws Exception{
        var j=open();var ids=sameBucket(3);for(String id:ids)j.transition(r(id,ABORTED),null);
        j.transition(r("different-stage",ABORTED),null);
        long gen=j.stats().generation();j.checkpointBatch(1);assertTrue(j.checkpointPending());
        var reopened=open();assertEquals(gen,reopened.stats().generation());
        assertEquals(4,reopened.receiptCount());for(String id:ids)assertEquals(r(id,ABORTED),reopened.receipt(id));
    }
    @Test void sameBucketModifiedAfterStagingMergesAllChanges()throws Exception{
        var j=open();var ids=sameBucket(3);j.transition(r(ids.get(0),ABORTED),null);
        j.transition(r("different-stage",ABORTED),null);j.checkpointBatch(1);assertTrue(j.checkpointPending());
        j.transition(r(ids.get(1),APPLIED),null);flush(j);var back=open();
        assertEquals(3,back.receiptCount());assertEquals(r(ids.get(0),ABORTED),back.receipt(ids.get(0)));
        assertEquals(r(ids.get(1),APPLIED),back.receipt(ids.get(1)));assertNull(back.receipt(ids.get(2)));
    }
    @Test void missingColdShardIsFatalNotAMiss()throws Exception{
        var j=open();j.transition(r("deleted",APPLIED),null);flush(j);var back=open();
        Path file;try(var files=Files.list(temp.resolve("current/receipts"))){file=files.findFirst().orElseThrow();}
        Files.delete(file);assertThrows(UncheckedIOException.class,()->back.receipt("deleted"));
        assertNotNull(back.admissionRejectReason());
        assertThrows(IOException.class,()->back.transition(r("new",APPLIED),null));
    }
    @Test void corruptedColdRecordIsNeverSilentlyReplaced()throws Exception{
        var j=open();j.transition(r("corrupt",APPLIED),null);flush(j);var back=open();
        Path file;try(var files=Files.list(temp.resolve("current/receipts"))){file=files.findFirst().orElseThrow();}
        byte[] bytes=Files.readAllBytes(file);bytes[bytes.length-1]^=1;Files.write(file,bytes);
        assertThrows(UncheckedIOException.class,()->back.receipt("corrupt"));assertNotNull(back.admissionRejectReason());
    }
    @Test void oversizedRecordBypassesCacheButRemainsReadable()throws Exception{
        var j=new WorldPersistentJournal(temp.resolve("current"),temp.resolve("absent.wal"),0);
        j.transition(r("uncached",APPLIED),null);flush(j);
        assertEquals(r("uncached",APPLIED),j.receipt("uncached"));assertEquals(0,j.receiptMemoryStats().cachedReceipts());
    }
    @Test void exactMatchesDoNotAliasEvenWhenFortyBitCandidateHashesCollide()throws Exception{
        String a="receipt-collision-122363",b="receipt-collision-1891807";
        var hash=MessageDigest.getInstance("SHA-256");
        assertArrayEquals(java.util.Arrays.copyOf(hash.digest(a.getBytes(java.nio.charset.StandardCharsets.UTF_8)),5),
                java.util.Arrays.copyOf(hash.digest(b.getBytes(java.nio.charset.StandardCharsets.UTF_8)),5));
        var j=open();j.transition(r(a,APPLIED),null);flush(j);
        assertNull(j.receipt(b));j.transition(r(b,ABORTED),null);flush(j);
        var back=open();assertEquals(r(a,APPLIED),back.receipt(a));assertEquals(r(b,ABORTED),back.receipt(b));
    }
    @Test void verifiedAbsentCandidateRequiresNoDiskIo()throws Exception{
        var ids=sameBucket(2);var j=open();j.transition(r(ids.get(0),APPLIED),null);flush(j);
        var back=open();assertNull(back.receipt(ids.get(1)));assertEquals(0,back.receiptMemoryStats().diskReads());
    }
    @Test void cacheHitDoesNotReadShardAgain()throws Exception{
        var j=open();j.transition(r("repeat",APPLIED),null);flush(j);var back=open();
        assertEquals(r("repeat",APPLIED),back.receipt("repeat"));long reads=back.receiptMemoryStats().diskReads();
        assertEquals(r("repeat",APPLIED),back.receipt("repeat"));assertEquals(reads,back.receiptMemoryStats().diskReads());
        assertEquals(1,back.receiptMemoryStats().cacheHits());
    }
    @Test void dirtyOverlaySurvivesStageAndIsClearedOnlyOnDurablePublish()throws Exception{
        var j=open();var id=sameBucket(1).getFirst();j.transition(r(id,ABORTED),null);
        j.transition(r("different-stage",APPLIED),null);j.checkpointBatch(1);
        assertEquals(2,j.receiptMemoryStats().dirtyReceipts());assertEquals(0,j.receiptMemoryStats().indexedReceipts());
        flush(j);assertEquals(0,j.receiptMemoryStats().dirtyReceipts());assertEquals(2,j.receiptMemoryStats().indexedReceipts());
    }
    @Test void unicodeIdsRoundTripThroughColdLookup()throws Exception{
        var j=open();String id="충돌-🧱";j.transition(r(id,APPLIED),null);flush(j);
        assertEquals(r(id,APPLIED),open().receipt(id));
    }
    @Test void corruptedBaseCannotBeMergedIntoCheckpoint()throws Exception{
        var ids=sameBucket(2);var j=open();j.transition(r(ids.getFirst(),APPLIED),null);flush(j);
        var back=open();back.transition(r(ids.getLast(),APPLIED),null);long generation=back.stats().generation();
        Path file;try(var files=Files.list(temp.resolve("current/receipts"))){file=files.findFirst().orElseThrow();}
        byte[] bytes=Files.readAllBytes(file);bytes[bytes.length-1]^=1;Files.write(file,bytes);
        assertThrows(IOException.class,()->flush(back));assertEquals(generation,back.stats().generation());
        assertNotNull(back.admissionRejectReason());
    }
}
