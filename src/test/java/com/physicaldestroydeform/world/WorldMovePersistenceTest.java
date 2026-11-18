package com.physicaldestroydeform.world;

import com.physicaldestroydeform.state.SparseStateKey;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Storage proof only; these tests do not claim a live World/Plot movement. */
class WorldMovePersistenceTest {
    @TempDir Path temp;
    private SparseStateKey key(int x) {
        return new SparseStateKey("minecraft:overworld","WORLD","minecraft:overworld",1,x,64,0,"minecraft:stone");
    }
    private WorldPersistentJournal open() throws IOException {
        return new WorldPersistentJournal(temp.resolve("store"),temp.resolve("absent.wal"));
    }
    private WorldReceiptJournal.Receipt receipt(String id,SparseStateKey key,WorldReceiptJournal.Status status) {
        return new WorldReceiptJournal.Receipt(id,key,status,
                status==WorldReceiptJournal.Status.APPLIED?"MOVE_POSTSTATE_CONFIRMED":"MOVE_PREPARED",100);
    }
    private WorldReceiptJournal.State state(SparseStateKey key,int damage,int remainder,int deformation) {
        return new WorldReceiptJournal.State(key,worldToken(key,1),damage,remainder,7,100,deformation);
    }
    private static String worldToken(SparseStateKey key,long revision) {
        try {return revision+"-"+java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(key.fingerprint().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private void prepare(WorldPersistentJournal j,String id,SparseStateKey from,
                         WorldReceiptJournal.State original,WorldReceiptJournal.State destination) throws IOException {
        j.transition(new WorldReceiptJournal.Receipt(id,from,WorldReceiptJournal.Status.PREPARED,
                WorldPersistentJournal.relocationPreparationReason(from,original,destination,0,0),100),null);
    }
    @Test void twoCellTransferSurvivesWalAndCheckpointAndCannotReplay() throws Exception {
        var j=open();var from=key(15);var to=key(16);var original=state(from,777,255,12);
        j.transition(receipt("seed",from,WorldReceiptJournal.Status.APPLIED),original);
        var destination=state(to,777,255,13);
        prepare(j,"move",from,original,destination);
        var done=receipt("move",from,WorldReceiptJournal.Status.APPLIED);
        j.finalizeRelocation(done,original,destination,0,0);
        var restored=open();
        assertNull(restored.get(from));assertEquals(destination,restored.get(to));
        assertEquals(1,restored.revision(from.dimension(),15,64,0));
        assertEquals(1,restored.revision(to.dimension(),16,64,0));
        assertEquals(done,restored.receipt("move"));
        assertThrows(IOException.class,()->restored.finalizeRelocation(done,original,destination,0,0));
        do {restored.checkpointBatch(128);} while(restored.checkpointPending());
        var checkpoint=open();assertNull(checkpoint.get(from));assertEquals(destination,checkpoint.get(to));
        assertEquals(done,checkpoint.receipt("move"));
    }
    @Test void deformationOnlyStateIsNotDroppedAndHistorySaturates() throws Exception {
        var j=open();var from=key(0);var to=key(1);var original=state(from,0,0,255);
        j.transition(receipt("seed",from,WorldReceiptJournal.Status.APPLIED),original);
        assertEquals(original,open().get(from));
        prepare(j,"move",from,original,state(to,0,0,255));
        j.finalizeRelocation(receipt("move",from,WorldReceiptJournal.Status.APPLIED),original,state(to,0,0,255),0,0);
        assertEquals(255,open().get(to).deformation());assertEquals(1,open().size());
    }
    @Test void noExistingSparseSourceStartsHistoryAtOne() throws Exception {
        var j=open();prepare(j,"move",key(0),null,state(key(1),0,0,1));
        j.finalizeRelocation(receipt("move",key(0),WorldReceiptJournal.Status.APPLIED),null,state(key(1),0,0,1),0,0);
        assertEquals(state(key(1),0,0,1),open().get(key(1)));
    }
    @Test void staleSourceDamageAndOccupiedSparseDestinationAreRejectedBeforeAppend() throws Exception {
        var j=open();var original=state(key(0),10,9,2);
        j.transition(receipt("seed",key(0),WorldReceiptJournal.Status.APPLIED),original);
        prepare(j,"move",key(0),original,state(key(1),10,9,3));
        long bytes=j.bytes();
        assertThrows(IOException.class,()->j.finalizeRelocation(receipt("move",key(0),WorldReceiptJournal.Status.APPLIED),
                state(key(0),11,9,2),state(key(1),11,9,3),0,0));
        assertEquals(bytes,j.bytes());
        j.transition(receipt("other",key(1),WorldReceiptJournal.Status.APPLIED),state(key(1),1,0,0));
        bytes=j.bytes();
        assertThrows(IOException.class,()->j.finalizeRelocation(receipt("move",key(0),WorldReceiptJournal.Status.APPLIED),
                original,state(key(1),10,9,3),0,0));
        assertEquals(bytes,j.bytes());assertEquals(original,open().get(key(0)));
    }
    @Test void revisionsRangeAndConservationAreEnforced() throws Exception {
        var j=open();var from=key(0);var original=state(from,12,7,3);
        j.transition(receipt("seed",from,WorldReceiptJournal.Status.APPLIED),original);
        prepare(j,"move",from,original,state(key(1),12,7,4));
        var done=receipt("move",from,WorldReceiptJournal.Status.APPLIED);long bytes=j.bytes();
        assertThrows(IOException.class,()->j.finalizeRelocation(done,original,state(key(1),12,7,4),1,0));
        assertThrows(IOException.class,()->j.finalizeRelocation(done,original,state(key(1),12,8,4),0,0));
        assertThrows(IOException.class,()->j.finalizeRelocation(done,original,state(key(1),12,7,3),0,0));
        assertThrows(IOException.class,()->j.finalizeRelocation(done,original,state(key(2),12,7,4),0,0));
        assertEquals(bytes,j.bytes());assertEquals(original,open().get(from));
    }
    @Test void preparedAloneNeverRelocatesAndLegacyRejectsHistory() throws Exception {
        var j=open();var from=key(0);var original=state(from,10,0,1);
        j.transition(receipt("seed",from,WorldReceiptJournal.Status.APPLIED),original);
        j.transition(receipt("move",from,WorldReceiptJournal.Status.PREPARED),null);
        var restored=open();assertEquals(original,restored.get(from));assertNull(restored.get(key(1)));
        assertEquals(WorldReceiptJournal.Status.PREPARED,restored.receipt("move").status());
        var legacy=new WorldReceiptJournal(temp.resolve("legacy.wal"));
        assertThrows(IOException.class,()->legacy.transition(receipt("seed",from,WorldReceiptJournal.Status.APPLIED),original));
        assertEquals(0,legacy.size());
    }
    @Test void preparedBreakCannotAuthorizeMoveAndMoveCannotUseOneCellCommit() throws Exception {
        var j=open();var from=key(0);var to=key(1);
        j.transition(receipt("break",from,WorldReceiptJournal.Status.PREPARED),null);
        assertThrows(IOException.class,()->j.finalizeRelocation(receipt("break",from,WorldReceiptJournal.Status.APPLIED),
                null,state(to,0,0,1),0,0));
        prepare(j,"move",from,null,state(to,0,0,1));
        assertThrows(IOException.class,()->j.transition(receipt("move",from,WorldReceiptJournal.Status.APPLIED),null));
        assertThrows(IOException.class,()->j.finalizeRelocation(receipt("move",from,WorldReceiptJournal.Status.APPLIED),
                null,state(key(-1),0,0,1),0,0));
        assertNull(open().get(to));
    }
    @Test void nextDamageKeepsTransferredDamageRemainderAndDeformation() throws Exception {
        var j=open();var from=key(0);var to=key(1);var original=state(from,12,99,2);
        j.transition(receipt("seed",from,WorldReceiptJournal.Status.APPLIED),original);
        prepare(j,"move",from,original,state(to,12,99,3));
        j.finalizeRelocation(receipt("move",from,WorldReceiptJournal.Status.APPLIED),original,state(to,12,99,3),0,0);
        var restored=open();var settings=com.physicaldestroydeform.DamageModel.Settings.defaults();
        var material=new com.physicaldestroydeform.MaterialProperties(80,1024,0,0,false);
        var core=new WorldCommitCore(restored,settings,64);
        var hit=new WorldCommitCore.Intent("next-hit",to,worldToken(to,1),1,material,7,101,true);
        core.submit(hit);
        var gateway=new WorldCommitCore.WorldGateway(){
            public boolean isServerThread(){return true;}
            public WorldCommitCore.Observation readIfLoaded(SparseStateKey key){
                return new WorldCommitCore.Observation(key.dimension(),key.fingerprint(),1,worldToken(key,1),
                        true,false,false,false,false,false);
            }
            public WorldCommitCore.Permission canDamage(SparseStateKey key,WorldCommitCore.Observation observed){
                return WorldCommitCore.Permission.ALLOW;
            }
            public WorldCommitCore.Permission canBreak(SparseStateKey key,WorldCommitCore.Observation observed){
                throw new AssertionError("small damage must not break");
            }
            public boolean breakVanillaOnce(SparseStateKey key){throw new AssertionError("unexpected break");}
        };
        assertEquals(WorldCommitCore.Outcome.APPLIED_DAMAGE,core.tick(gateway,102).getFirst().result());
        assertEquals(3,restored.get(to).deformation());
        assertTrue(restored.get(to).damage()*256L+restored.get(to).remainder()>=12*256L+99);
        assertEquals(restored.get(to),open().get(to));
    }
}
