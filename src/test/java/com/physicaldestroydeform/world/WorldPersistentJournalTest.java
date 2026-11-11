package com.physicaldestroydeform.world;

import com.physicaldestroydeform.state.SparseStateKey;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.CRC32;
import static org.junit.jupiter.api.Assertions.*;

/** 0.5.0 CAP-SPARSE-PERSIST integration/format and crash-boundary regression tests. */
class WorldPersistentJournalTest {
    private static final String OVERWORLD="minecraft:overworld";
    private static SparseStateKey key(String dim,int x,int y,int z){
        return new SparseStateKey(dim,"WORLD",dim,1,x,y,z,"minecraft:stone[axis=y]");
    }
    private static WorldReceiptJournal.State state(SparseStateKey k,int d,int r,long material){
        return new WorldReceiptJournal.State(k,"0-fingerprint-sha",d,r,material,100);
    }
    private static WorldReceiptJournal.Receipt receipt(String id,SparseStateKey k,WorldReceiptJournal.Status status){
        return new WorldReceiptJournal.Receipt(id,k,status,"TEST",100);
    }
    private record Area(Path old,Path current){
        WorldPersistentJournal open() throws IOException{return new WorldPersistentJournal(current,old);}
    }
    private static Area area()throws IOException{
        var dir=Files.createTempDirectory("pdd-050-test-").resolve("data");
        return new Area(dir.resolve("pdd-world-0.4.1.wal"),dir.resolve("pdd-world-0.5.0"));
    }
    private static void flush(WorldPersistentJournal journal)throws IOException{
        int passes=0;
        do{journal.checkpointBatch(WorldPersistentJournal.SAVE_DIRTY_BATCH);}
        while(journal.checkpointPending()&&++passes<2048);
        assertFalse(journal.checkpointPending());
    }
    @Test void severalDimensionsNegativeChunksAndSparseValuesRoundTrip()throws Exception{
        var a=area();var j=a.open();
        var keys=List.of(key(OVERWORLD,0,64,0),key(OVERWORLD,32,90,-17),
                key("minecraft:the_nether",-65,45,16),key("minecraft:the_end",3,32,80));
        for(int i=0;i<keys.size();i++){
            var s=state(keys.get(i),i+1,255-i,i+4);
            j.transition(receipt("applied-"+i,keys.get(i),WorldReceiptJournal.Status.APPLIED),s);
        }
        assertEquals(4,j.size());flush(j);
        assertEquals(4,j.stats().occupiedChunks());
        var back=a.open();assertEquals(4,back.size());assertEquals(4,back.receiptCount());
        for(int i=0;i<keys.size();i++){
            assertEquals(i+1,back.get(keys.get(i)).damage());
            assertEquals(255-i,back.get(keys.get(i)).remainder());
            assertEquals(i+4,back.get(keys.get(i)).materialGeneration());
            assertEquals(WorldReceiptJournal.Status.APPLIED,back.receipt("applied-"+i).status());
        }
        assertThrows(IllegalArgumentException.class,()->new SparseStateKey(
                OVERWORLD,"PLOT","bad",1,0,0,0,"f"));
    }
    @Test void noDamageZeroEntriesAndMaterialChangeDoesNotRecalculatePastDamage()throws Exception{
        var a=area();var j=a.open();var k=key(OVERWORLD,1,64,1);
        j.transition(receipt("A",k,WorldReceiptJournal.Status.APPLIED),state(k,20,45,5));
        j.transition(receipt("B",k,WorldReceiptJournal.Status.APPLIED),state(k,20,45,9));
        assertEquals(20,j.get(k).damage());assertEquals(9,j.get(k).materialGeneration());
        flush(j);var again=a.open();
        assertEquals(20,again.get(k).damage());assertEquals(45,again.get(k).remainder());
        again.transition(receipt("C",k,WorldReceiptJournal.Status.APPLIED),state(k,0,0,10));
        assertNull(again.get(k));flush(again);assertNull(a.open().get(k));
    }
    @Test void sameBlockReinstalledGetsNewDurableGenerationAndNoGhostDamage()throws Exception{
        var a=area();var j=a.open();var k=key(OVERWORLD,1,70,1);
        j.transition(receipt("pre-replacement",k,WorldReceiptJournal.Status.APPLIED),state(k,120,10,1));
        assertEquals(1,j.replaceBlock(OVERWORLD,1,70,1));
        assertNull(j.get(k));assertEquals(1,j.revision(OVERWORLD,1,70,1));
        var restarted=a.open();assertNull(restarted.get(k));
        assertEquals(1,restarted.revision(OVERWORLD,1,70,1));
        assertEquals(2,restarted.replaceBlock(OVERWORLD,1,70,1));
        flush(restarted);
        assertEquals(2,a.open().revision(OVERWORLD,1,70,1));
        assertNotNull(a.open().receipt("pre-replacement"));
    }
    @Test void preparedAndDeferredReceiptsStayQuarantinableAcrossRestart()throws Exception{
        var a=area();var j=a.open();var k=key(OVERWORLD,2,64,2);
        j.transition(receipt("prep",k,WorldReceiptJournal.Status.PREPARED),null);
        j.transition(receipt("queued",k,WorldReceiptJournal.Status.DEFERRED),null);
        var reboot=a.open();assertTrue(reboot.recoveredUncertain("prep"));
        assertTrue(reboot.recoveredUncertain("queued"));
        assertEquals(2,reboot.uncertain().size());
        reboot.transition(receipt("prep",k,WorldReceiptJournal.Status.PARTIAL_QUARANTINED),null);
        reboot.transition(receipt("queued",k,WorldReceiptJournal.Status.PARTIAL_QUARANTINED),null);
        assertEquals(0,reboot.uncertain().size());
        assertThrows(IllegalArgumentException.class,()->reboot.transition(
                receipt("prep",k,WorldReceiptJournal.Status.APPLIED),null));
        assertEquals(WorldReceiptJournal.Status.PARTIAL_QUARANTINED,a.open().receipt("prep").status());
    }
    @Test void oldSchema2MigratesExactlyAndPreventsDowngradeDivergence()throws Exception{
        var a=area();Files.createDirectories(a.old().getParent());
        var old=new WorldReceiptJournal(a.old());var k=key(OVERWORLD,4,64,4);
        old.transition(receipt("legacy-applied",k,WorldReceiptJournal.Status.APPLIED),state(k,120,255,7));
        old.transition(receipt("legacy-prepared",k,WorldReceiptJournal.Status.PREPARED),null);
        old.transition(receipt("legacy-abort",k,WorldReceiptJournal.Status.ABORTED),null);
        byte[] legacyBytes=Files.readAllBytes(a.old());
        var modern=a.open();assertTrue(modern.migrated());
        assertEquals(3,modern.receiptCount());assertEquals(120,modern.get(k).damage());
        assertEquals(255,modern.get(k).remainder());
        assertTrue(modern.recoveredUncertain("legacy-prepared"));
        assertArrayEquals(legacyBytes,Files.readAllBytes(a.old()),"migration must be read-only");
        assertEquals(3,a.open().receiptCount());
        old.transition(receipt("legacy-downgrade-write",k,WorldReceiptJournal.Status.APPLIED),null);
        assertThrows(IOException.class,a::open,"0.4.1 writes after 0.5.0 migration must not fork state");
    }
    @Test void stagingInterruptedBeforeManifestNeverLosesOriginalWal()throws Exception{
        var a=area();var j=a.open();
        for(int i=0;i<12;i++){
            var k=key(OVERWORLD,i*16,64,i*16);
            j.transition(receipt("chunk"+i,k,WorldReceiptJournal.Status.APPLIED),state(k,i+1,0,1));
        }
        long gen=j.stats().generation();
        int staged=j.checkpointBatch(1);
        assertEquals(1,staged);assertTrue(j.checkpointPending());
        var reboot=a.open(); // incomplete snapshot generation is ignored
        assertEquals(gen,reboot.stats().generation());
        assertEquals(12,reboot.size());
        flush(reboot);assertEquals(12,a.open().size());
    }
    @Test void checkpointManifestAndShardCorruptionFailClosed()throws Exception{
        var a=area();var j=a.open();var k=key(OVERWORLD,2,70,5);
        j.transition(receipt("OK",k,WorldReceiptJournal.Status.APPLIED),state(k,1,2,3));flush(j);
        Path shard;
        try(var walk=Files.walk(a.current())){
            shard=walk.filter(p->p.toString().endsWith(".pds")).findFirst().orElseThrow();
        }
        flipLastByte(shard);
        assertThrows(IOException.class,a::open,"unreadable referenced shard cannot be silently reset");
        var b=area();var other=b.open();
        other.transition(receipt("B",k,WorldReceiptJournal.Status.APPLIED),state(k,10,20,2));
        flush(other);
        flipLastByte(b.current().resolve("checkpoint.manifest"));
        assertThrows(IOException.class,b::open);
    }
    @Test void unsupportedSchemaAndCorruptRecoveryWalFailClosed()throws Exception{
        var a=area();var j=a.open();var k=key(OVERWORLD,2,64,2);
        j.transition(receipt("corrupt",k,WorldReceiptJournal.Status.APPLIED),state(k,10,0,2));
        flipLastByte(a.current().resolve("recovery-1.wal"));
        assertThrows(IOException.class,a::open);
        var b=area();b.open();
        Path m=b.current().resolve("checkpoint.manifest");var bytes=Files.readAllBytes(m);
        bytes[7]=(byte)99;Files.write(m,bytes);
        assertThrows(IOException.class,b::open);
    }
    @Test void recoveredDamageAndReceiptAreAtomicAtWalFrameBoundary()throws Exception{
        var a=area();var j=a.open();var k=key(OVERWORLD,18,64,18);
        j.transition(receipt("D",k,WorldReceiptJournal.Status.APPLIED),state(k,34,200,3));
        var reload=a.open();
        assertEquals(34,reload.get(k).damage());
        assertEquals(WorldReceiptJournal.Status.APPLIED,reload.receipt("D").status());
        assertThrows(IllegalArgumentException.class,()->reload.transition(
                receipt("D",k,WorldReceiptJournal.Status.APPLIED),state(k,35,0,3)));
        assertThrows(IllegalArgumentException.class,()->reload.transition(
                receipt("D",key(OVERWORLD,19,64,18),WorldReceiptJournal.Status.ABORTED),null));
    }
    @Test void chunkLimit4096ExplicitlyRejectsWithoutDroppingRecordedData()throws Exception{
        var a=area();Files.createDirectories(a.old().getParent());
        createLegacyFixture(a.old(),4096,true);
        var j=a.open();assertEquals(4096,j.size());
        var extra=key(OVERWORLD,0,16,0);
        assertFalse(j.canStore(extra));
        assertThrows(IOException.class,()->j.transition(receipt("4097",extra,WorldReceiptJournal.Status.APPLIED),
                state(extra,4,1,1)));
        assertEquals(4096,j.size());
        assertEquals(4096,a.open().size());
    }
    @Test void sixtyThousandLegacyReceiptsCanGrowBeyondOldLimitAndStayDuplicateProof()throws Exception{
        var a=area();Files.createDirectories(a.old().getParent());
        createLegacyFixture(a.old(),60_000,false);
        var j=a.open();assertEquals(60_000,j.receiptCount());
        var key=key(OVERWORLD,0,50,0);
        j.transition(receipt("new-60001",key,WorldReceiptJournal.Status.ABORTED),null);
        assertEquals(60_001,j.receiptCount());
        flush(j);var reboot=a.open();
        assertEquals(60_001,reboot.receiptCount());
        assertNotNull(reboot.receipt("receipt-0"));
        assertNotNull(reboot.receipt("receipt-59999"));
        assertNotNull(reboot.receipt("new-60001"));
        assertThrows(IllegalArgumentException.class,()->reboot.transition(
                receipt("receipt-0",key(OVERWORLD,1,64,1),WorldReceiptJournal.Status.APPLIED),null));
    }
    @Test void legacyCRCOrUnsupportedSchemaNeverStartsEmptyNewStore()throws Exception{
        var a=area();Files.createDirectories(a.old().getParent());
        var old=new WorldReceiptJournal(a.old());var k=key(OVERWORLD,1,70,2);
        old.transition(receipt("A",k,WorldReceiptJournal.Status.APPLIED),state(k,10,0,1));
        flipLastByte(a.old());
        assertThrows(IOException.class,a::open);
        assertFalse(Files.exists(a.current().resolve("checkpoint.manifest")));
        var b=area();Files.createDirectories(b.old().getParent());
        Files.write(b.old(),new byte[]{0x50,0x44,0x57,0x32,0,0,0,42});
        assertThrows(IOException.class,b::open);
    }
    @Test void incompleteFinalWalAppendIsTruncatedWithExplicitRecoveryEvidence()throws Exception{
        var a=area();var j=a.open();var k=key(OVERWORLD,4,50,9);
        j.transition(receipt("complete",k,WorldReceiptJournal.Status.APPLIED),state(k,70,8,1));
        Path wal=a.current().resolve("recovery-1.wal");
        long before=Files.size(wal);
        Files.write(wal,new byte[]{0,0,0,50,13},StandardOpenOption.APPEND);
        var repaired=a.open();
        assertEquals(5,repaired.repairedTailBytes());
        assertEquals(before,Files.size(wal));
        assertEquals(70,repaired.get(k).damage());
        assertNotNull(repaired.receipt("complete"));
        repaired.transition(receipt("after-repair",k,WorldReceiptJournal.Status.ABORTED),null);
        assertNotNull(a.open().receipt("after-repair"));
    }
    @Test void checkpointIOFailurePreservesLastCommittedManifestAndWal()throws Exception{
        var a=area();var j=a.open();var k=key(OVERWORLD,5,64,5);
        j.transition(receipt("before-failed-checkpoint",k,WorldReceiptJournal.Status.APPLIED),
                state(k,90,40,1));
        // Make the receipt directory unusable without touching committed data.
        Path receiptDir=a.current().resolve("receipts");
        assertFalse(Files.exists(receiptDir));
        Files.write(receiptDir,new byte[]{0});
        // The checkpoint intentionally has a soft 2ms budget: a successful
        // chunk write may finish the first call before the receipt shard is tried.
        assertThrows(IOException.class,()->{
            int attempts=0;
            do {
                j.checkpointBatch(128);
                if(++attempts>1024)throw new AssertionError("checkpoint did not visit receipt shard");
            }while(j.checkpointPending());
        });
        assertNotNull(j.receipt("before-failed-checkpoint"));
        Files.delete(receiptDir);
        var restart=a.open();
        assertEquals(90,restart.get(k).damage());
        assertNotNull(restart.receipt("before-failed-checkpoint"));
        flush(restart);
        assertEquals(90,a.open().get(k).damage());
    }
    @Test void newWorldLegacyWalAppearanceIsDetectedAsUnsafeDowngrade()throws Exception{
        var a=area();a.open();new WorldReceiptJournal(a.old());
        assertThrows(IOException.class,a::open);
    }
    private static void flipLastByte(Path path)throws IOException {
        byte[] bytes=Files.readAllBytes(path);bytes[bytes.length-1]^=0x45;Files.write(path,bytes);
    }
    /** Efficient true schema-2 frames avoid 60,000 per-record fsync calls in CI. */
    private static void createLegacyFixture(Path wal,int n,boolean withDamage)throws IOException {
        try(var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(wal)))){
            out.writeInt(0x50445732);out.writeInt(2);
            for(int i=0;i<n;i++){
                var key=withDamage?key(OVERWORLD,(i&15),i>>8,(i>>4)&15):key(OVERWORLD,1,64,1);
                var body=new ByteArrayOutputStream();
                try(var record=new DataOutputStream(body)){
                    record.writeByte(1);record.writeUTF("receipt-"+i);writeLegacyKey(record,key);
                    record.writeByte(WorldReceiptJournal.Status.APPLIED.ordinal());record.writeUTF("OK");
                    record.writeLong(100);record.writeBoolean(withDamage);
                    if(withDamage){writeLegacyKey(record,key);record.writeUTF("0-fingerprint-sha");
                        record.writeByte(10);record.writeByte(128);record.writeLong(1);record.writeLong(100);}
                }
                byte[] b=body.toByteArray();var crc=new CRC32();crc.update(b);
                out.writeInt(b.length);out.writeInt((int)crc.getValue());out.write(b);
            }
        }
        // Test fixture must itself be a valid old-version WAL.
        var old=new WorldReceiptJournal(wal);assertEquals(n,old.receiptCount());
        assertEquals(withDamage?n:0,old.size());
    }
    private static void writeLegacyKey(DataOutputStream out,SparseStateKey key)throws IOException {
        out.writeUTF(key.dimension());out.writeUTF(key.ownerSpace());out.writeUTF(key.ownerId());
        out.writeLong(key.ownerGeneration());out.writeInt(key.x());out.writeInt(key.y());
        out.writeInt(key.z());out.writeUTF(key.fingerprint());
    }
}
