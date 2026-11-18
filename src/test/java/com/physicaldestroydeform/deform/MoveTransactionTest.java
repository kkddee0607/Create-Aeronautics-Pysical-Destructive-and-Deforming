package com.physicaldestroydeform.deform;

import com.physicaldestroydeform.MaterialProperties;
import com.physicaldestroydeform.state.SparseStateKey;
import com.physicaldestroydeform.world.WorldPersistentJournal;
import com.physicaldestroydeform.world.WorldReceiptJournal;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MoveTransactionTest {
    @TempDir Path temp;
    private SparseStateKey key(int x,String fingerprint) {
        return new SparseStateKey("minecraft:overworld","WORLD","minecraft:overworld",1,x,64,0,fingerprint);
    }
    private WorldPersistentJournal damage() throws IOException {
        return new WorldPersistentJournal(temp.resolve("damage"),temp.resolve("missing.wal"));
    }
    private MoveTransactionJournal moves() throws IOException {return new MoveTransactionJournal(temp.resolve("moves"));}
    private MoveTransactionJournal.BeforeImage before() throws Exception {
        var source=key(0,"minecraft:stone");var target=key(1,"minecraft:stone");
        String token="1-"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(target.fingerprint().getBytes(StandardCharsets.UTF_8)));
        return new MoveTransactionJournal.BeforeImage("move-1","direct-1",source,key(1,"minecraft:air"),null,
                new WorldReceiptJournal.State(target,token,0,0,1,100,1),0,0,100,1300,20,3);
    }
    private void seed(WorldPersistentJournal damage,MoveTransactionJournal.BeforeImage b) throws IOException {
        damage.transition(new WorldReceiptJournal.Receipt(b.directApplyId(),b.source(),WorldReceiptJournal.Status.APPLIED,
                "DIRECT",100),null);
    }
    private MoveTransactionJournal.BeforeImage reserved() throws Exception {
        var b=before();
        return new MoveTransactionJournal.BeforeImage(b.id(),b.directApplyId(),b.source(),
                key(1,"minecraft:bedrock"),b.sourceState(),b.destinationAfter(),0,0,100,1300,20,3,
                new MaterialProperties(0,100,100,0,false),"revision",new byte[]{1,2,3},8,3,1024);
    }
    private class Fake implements MoveTransactionExecutor.Gateway {
        int mutations,releases,quarantines,reads;boolean throwAfterMutation;
        MoveTransactionExecutor.Gate gate=MoveTransactionExecutor.Gate.APPROVED;
        MoveTransactionExecutor.Readback result=MoveTransactionExecutor.Readback.CONFIRMED;
        public boolean serverThread(){return true;}
        public boolean acquire(MoveTransactionJournal.BeforeImage b){return true;}
        public MoveTransactionExecutor.Gate validate(MoveTransactionJournal.BeforeImage b){return gate;}
        public MoveTransactionExecutor.Readback mutateOnce(MoveTransactionJournal.BeforeImage b){
            try {
                assertEquals(MoveTransactionJournal.Phase.PREPARED,moves().get(b.id()).phase());
                assertEquals(WorldReceiptJournal.Status.PREPARED,damage().receipt(b.id()).status());
            }catch(IOException failure){throw new AssertionError(failure);}
            mutations++;if(throwAfterMutation)throw new IllegalStateException("setter threw");return result;
        }
        public MoveTransactionExecutor.Readback readback(MoveTransactionJournal.BeforeImage b){reads++;return result;}
        public void release(MoveTransactionJournal.BeforeImage b){releases++;}
        public void quarantine(MoveTransactionJournal.BeforeImage b,String reason){quarantines++;}
    }
    @Test void bothJournalsArePreparedBeforeSingleMutationAndDuplicateCannotMutate() throws Exception {
        var d=damage();var m=moves();var b=before();seed(d,b);var gateway=new Fake();
        var executor=new MoveTransactionExecutor(m,d);
        assertEquals(MoveTransactionExecutor.Outcome.APPLIED,executor.execute(b,gateway,100));
        assertEquals(1,gateway.mutations);assertEquals(1,gateway.releases);
        assertEquals(MoveTransactionJournal.Phase.APPLIED,moves().get(b.id()).phase());
        assertEquals(b.destinationAfter(),damage().get(b.destinationAfter().key()));
        assertEquals(MoveTransactionExecutor.Outcome.DUPLICATE,executor.execute(b,gateway,101));
        assertEquals(1,gateway.mutations);
    }
    @Test void awaitingHeatMapDoesNotRepeatMutationOrPublishSparseEarly() throws Exception {
        var d=damage();var m=moves();var b=before();seed(d,b);var gateway=new Fake();
        gateway.result=MoveTransactionExecutor.Readback.WAITING;
        var executor=new MoveTransactionExecutor(m,d);
        assertEquals(MoveTransactionExecutor.Outcome.DEFERRED,executor.execute(b,gateway,100));
        assertNull(d.get(b.destinationAfter().key()));assertEquals(0,gateway.releases);
        executor.tick(gateway,101,1);assertEquals(1,gateway.mutations);assertEquals(1,gateway.reads);
        gateway.result=MoveTransactionExecutor.Readback.CONFIRMED;executor.tick(gateway,102,1);
        assertEquals(1,gateway.mutations);assertEquals(1,gateway.releases);
        assertEquals(b.destinationAfter(),damage().get(b.destinationAfter().key()));
    }
    @Test void cancellationRequiresVerifiedBothBeforeImagesAndNoStateTransfer() throws Exception {
        var d=damage();var m=moves();var b=before();seed(d,b);var gateway=new Fake();
        gateway.result=MoveTransactionExecutor.Readback.RESTORED_BEFORE_IMAGE;
        assertEquals(MoveTransactionExecutor.Outcome.BLOCKED,new MoveTransactionExecutor(m,d).execute(b,gateway,100));
        assertEquals(MoveTransactionJournal.Phase.ROLLED_BACK,moves().get(b.id()).phase());
        assertNull(damage().get(b.destinationAfter().key()));assertEquals(1,gateway.releases);
    }
    @Test void setterExceptionAndRestartNeverRepeatSetters() throws Exception {
        var d=damage();var m=moves();var b=before();seed(d,b);var gateway=new Fake();gateway.throwAfterMutation=true;
        assertEquals(MoveTransactionExecutor.Outcome.QUARANTINED,new MoveTransactionExecutor(m,d).execute(b,gateway,100));
        assertEquals(1,gateway.mutations);assertEquals(0,gateway.releases);assertEquals(1,gateway.quarantines);
        var reboot=new MoveTransactionExecutor(moves(),damage());var after=new Fake();reboot.recover(after);
        assertEquals(0,after.mutations);assertEquals(1,after.quarantines);
        assertNull(damage().get(b.destinationAfter().key()));
    }
    @Test void preparedCrashNeverInfersSuccessAndExpiryDoesNotRetryMutation() throws Exception {
        var d=damage();var m=moves();var b=before();seed(d,b);var gateway=new Fake();
        gateway.result=MoveTransactionExecutor.Readback.WAITING;
        var executor=new MoveTransactionExecutor(m,d);executor.execute(b,gateway,100);
        executor.tick(gateway,1301,1);
        assertEquals(1,gateway.mutations);assertEquals(1,gateway.quarantines);
        assertEquals(MoveTransactionJournal.Phase.QUARANTINED,moves().get(b.id()).phase());
    }
    @Test void readbackAfterDeadlineDoesNotPublishEvenIfGatewayReportsConfirmed() throws Exception {
        var d=damage();var m=moves();var b=before();seed(d,b);var gateway=new Fake();
        gateway.result=MoveTransactionExecutor.Readback.WAITING;
        var executor=new MoveTransactionExecutor(m,d);executor.execute(b,gateway,100);
        gateway.result=MoveTransactionExecutor.Readback.CONFIRMED;
        executor.tick(gateway,1301,1);
        assertEquals(MoveTransactionJournal.Phase.QUARANTINED,moves().get(b.id()).phase());
        assertNull(damage().get(b.destinationAfter().key()));assertEquals(1,gateway.mutations);
    }
    @Test void missingSourceReceiptOrUnverifiedDirectionGatewayNeverMutates() throws Exception {
        var d=damage();var m=moves();var b=before();var gateway=new Fake();
        assertEquals(MoveTransactionExecutor.Outcome.UNKNOWN,new MoveTransactionExecutor(m,d).execute(b,gateway,100));
        seed(d,b);gateway.gate=MoveTransactionExecutor.Gate.UNKNOWN;
        assertEquals(MoveTransactionExecutor.Outcome.UNKNOWN,new MoveTransactionExecutor(m,d).execute(b,gateway,100));
        assertEquals(0,gateway.mutations);assertNull(m.get(b.id()));
    }
    @Test void crcCorruptionAndAliasedIdFailClosed() throws Exception {
        var m=moves();var b=before();assertTrue(m.prepare(b));assertFalse(m.prepare(b));
        var alias=new MoveTransactionJournal.BeforeImage(b.id(),"different-direct",b.source(),b.destinationBefore(),
                b.sourceState(),b.destinationAfter(),0,0,100,1300,20,3);
        assertThrows(IOException.class,()->m.prepare(alias));
        Path file;try(var files=Files.list(temp.resolve("moves"))){file=files.filter(p->p.toString().endsWith(".pmv")).findFirst().orElseThrow();}
        byte[] bytes=Files.readAllBytes(file);bytes[bytes.length-1]^=1;Files.write(file,bytes);
        assertThrows(IOException.class,this::moves);
    }
    @Test void verifiedOccupiedTargetPersistsReservedQBeforeAdditionalDamageWithoutSetters() throws Exception {
        var d=damage();var m=moves();var b=reserved();seed(d,b);
        var g=new Fake(){
            @Override public MoveTransactionExecutor.Settlement settleBlocked(MoveTransactionJournal.BeforeImage image){
                assertEquals(MoveTransactionJournal.Phase.BLOCKED_PENDING,m.get(image.id()).phase());
                assertEquals(3.0,image.blockedAppliedQ());assertEquals(8.0,image.blockedCandidateQ());
                assertArrayEquals(new byte[]{1,2,3},image.bePayload());
                try {
                    d.transition(new WorldReceiptJournal.Receipt(image.blockedApplyId(),image.source(),
                            WorldReceiptJournal.Status.APPLIED,"RESERVED_EXTRA",100),null);
                }catch(IOException failure){throw new AssertionError(failure);}
                return MoveTransactionExecutor.Settlement.SETTLED;
            }
        };
        g.gate=MoveTransactionExecutor.Gate.BLOCKED;
        var executor=new MoveTransactionExecutor(m,d);
        assertEquals(MoveTransactionExecutor.Outcome.BLOCKED,executor.execute(b,g,100));
        assertEquals(0,g.mutations);assertEquals(1,g.releases);
        assertEquals(MoveTransactionJournal.Phase.ROLLED_BACK,moves().get(b.id()).phase());
        assertEquals(WorldReceiptJournal.Status.APPLIED,damage().receipt(b.blockedApplyId()).status());
        assertEquals(MoveTransactionExecutor.Outcome.DUPLICATE,executor.execute(b,g,101));
        assertEquals(b,moves().get(b.id()).before()); // material and BE payload survive reopen
        b.bePayload()[0]=0;assertArrayEquals(new byte[]{1,2,3},b.bePayload());
    }
    @Test void restartAfterAdditionalDamageReceiptDoesNotSettleSecondTime() throws Exception {
        var d=damage();var m=moves();var b=reserved();seed(d,b);
        var g=new Fake(){@Override public MoveTransactionExecutor.Settlement settleBlocked(MoveTransactionJournal.BeforeImage image){
            return MoveTransactionExecutor.Settlement.WAITING;}};
        g.gate=MoveTransactionExecutor.Gate.BLOCKED;
        assertEquals(MoveTransactionExecutor.Outcome.DEFERRED,new MoveTransactionExecutor(m,d).execute(b,g,100));
        assertEquals(MoveTransactionJournal.Phase.BLOCKED_PENDING,moves().get(b.id()).phase());
        d.transition(new WorldReceiptJournal.Receipt(b.blockedApplyId(),b.source(),
                WorldReceiptJournal.Status.APPLIED,"RESERVED_EXTRA",101),null);
        var reopenedMoves=moves();var recovered=new MoveTransactionExecutor(reopenedMoves,damage());
        var after=new Fake(){@Override public MoveTransactionExecutor.Settlement settleBlocked(MoveTransactionJournal.BeforeImage image){
            fail("terminal blocked receipt must bypass settlement callback");return MoveTransactionExecutor.Settlement.UNCERTAIN;}};
        recovered.recover(after);recovered.tick(after,102,1);
        assertEquals(0,after.mutations);assertEquals(MoveTransactionJournal.Phase.ROLLED_BACK,reopenedMoves.get(b.id()).phase());
    }
    @Test void blockedReservationExpiresWithoutNewDamageOrSetter() throws Exception {
        var d=damage();var m=moves();var b=reserved();seed(d,b);
        var g=new Fake(){@Override public MoveTransactionExecutor.Settlement settleBlocked(MoveTransactionJournal.BeforeImage image){
            return MoveTransactionExecutor.Settlement.WAITING;}};
        g.gate=MoveTransactionExecutor.Gate.BLOCKED;
        var executor=new MoveTransactionExecutor(m,d);executor.execute(b,g,100);
        executor.tick(g,1301,1);
        assertEquals(0,g.mutations);assertNull(d.receipt(b.blockedApplyId()));
        assertEquals(MoveTransactionJournal.Phase.ROLLED_BACK,m.get(b.id()).phase());
        assertEquals("BLOCKED_RESERVE_EXPIRED_UNUSED",m.get(b.id()).reason());
    }
    @Test void crashBetweenRollbackReceiptAndBlockedJournalRetainsSealedQWithoutMoveReplay() throws Exception {
        var d=damage();var m=moves();var b=reserved();seed(d,b);
        m.prepare(b);
        d.transition(new WorldReceiptJournal.Receipt(b.id(),b.source(),WorldReceiptJournal.Status.ABORTED,
                "BOTH_BEFORE_IMAGES_VERIFIED",101),null);
        var reopenedMoves=moves();var reopenedDamage=damage();
        var executor=new MoveTransactionExecutor(reopenedMoves,reopenedDamage);
        var g=new Fake(){@Override public MoveTransactionExecutor.Settlement settleBlocked(MoveTransactionJournal.BeforeImage image){
            try{reopenedDamage.transition(new WorldReceiptJournal.Receipt(image.blockedApplyId(),image.source(),
                    WorldReceiptJournal.Status.APPLIED,"RESERVED_EXTRA",102),null);}
            catch(IOException failure){throw new AssertionError(failure);}
            return MoveTransactionExecutor.Settlement.SETTLED;
        }};
        executor.recover(g);
        assertTrue(executor.pending());
        assertEquals(MoveTransactionJournal.Phase.BLOCKED_PENDING,reopenedMoves.get(b.id()).phase());
        executor.tick(g,102,1);assertFalse(executor.pending());
        assertEquals(0,g.mutations);assertEquals(0,g.quarantines);
        assertEquals(MoveTransactionJournal.Phase.ROLLED_BACK,reopenedMoves.get(b.id()).phase());
    }
    @Test void uncertainAdditionalDamageIsQuarantinedWithoutSecondCallback() throws Exception {
        var d=damage();var m=moves();var b=reserved();seed(d,b);m.block(b);
        d.transition(new WorldReceiptJournal.Receipt(b.blockedApplyId(),b.source(),
                WorldReceiptJournal.Status.PREPARED,"RESERVED_EXTRA",101),null);
        var reopenedMoves=moves();var executor=new MoveTransactionExecutor(reopenedMoves,damage());
        var g=new Fake(){@Override public MoveTransactionExecutor.Settlement settleBlocked(MoveTransactionJournal.BeforeImage image){
            fail("uncertain receipt must not be applied again");return MoveTransactionExecutor.Settlement.UNCERTAIN;}};
        executor.recover(g);executor.tick(g,102,1);
        assertEquals(0,g.mutations);assertEquals(1,g.quarantines);
        assertEquals(MoveTransactionJournal.Phase.QUARANTINED,reopenedMoves.get(b.id()).phase());
    }
}
