package com.physicaldestroydeform.world;

import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.MaterialProperties;
import com.physicaldestroydeform.state.SparseStateKey;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual 0.5.0 journal wired into unchanged safe World Q -> CAS -> break pipeline. */
class WorldPersistentCommitIntegrationTest {
    private static final String DIM="minecraft:overworld";
    private static final MaterialProperties STONE=new MaterialProperties(59,91,10,9,false);
    private record Data(Path legacy,Path store) {
        WorldPersistentJournal open()throws Exception{return new WorldPersistentJournal(store,legacy);}
    }
    private static Data area()throws Exception{
        var dir=Files.createTempDirectory("pdd-world-persist-core-");
        return new Data(dir.resolve("pdd-world-0.4.1.wal"),dir.resolve("pdd-world-0.5.0"));
    }
    private static SparseStateKey key(int x){return new SparseStateKey(DIM,"WORLD",DIM,1,x,64,5,"minecraft:stone");}
    private static WorldCommitCore.Intent intent(String id,SparseStateKey key,double q){
        return new WorldCommitCore.Intent(id,key,"0-real-fingerprint",q,STONE,1,200,true);
    }
    private static class Fake implements WorldCommitCore.WorldGateway {
        boolean loaded=true,air,authorized=true;
        int breakCalls,drops;
        @Override public boolean isServerThread(){return true;}
        @Override public WorldCommitCore.Observation readIfLoaded(SparseStateKey key){
            if(!loaded)return null;
            return new WorldCommitCore.Observation(DIM,key.fingerprint(),1,"0-real-fingerprint",
                    true,air,false,false,false,false);
        }
        @Override public WorldCommitCore.Permission canDamage(SparseStateKey key,WorldCommitCore.Observation expected){
            return authorized?WorldCommitCore.Permission.ALLOW:WorldCommitCore.Permission.DENY;
        }
        @Override public WorldCommitCore.Permission canBreak(SparseStateKey key,WorldCommitCore.Observation expected){
            return authorized?WorldCommitCore.Permission.ALLOW:WorldCommitCore.Permission.DENY;
        }
        @Override public boolean breakVanillaOnce(SparseStateKey key){
            breakCalls++;if(air)return false;air=true;drops++;return true;
        }
    }
    @Test void preparedIsFsyncedBeforeVanillaBreakAndRepeatedApplyIdDoesNotDropTwice()throws Exception{
        var data=area();var journal=data.open();var k=key(4);
        var core=new WorldCommitCore(journal,DamageModel.Settings.defaults(),64);
        var world=new Fake(){
            @Override public boolean breakVanillaOnce(SparseStateKey target){
                try{
                    // Independent read from disk during the gateway mutation call.
                    var fromDisk=data.open();
                    assertEquals(WorldReceiptJournal.Status.PREPARED,fromDisk.receipt("single-break").status());
                    assertTrue(fromDisk.recoveredUncertain("single-break"));
                }catch(Exception broken){throw new AssertionError(broken);}
                return super.breakVanillaOnce(target);
            }
        };
        core.submit(intent("single-break",k,5000));
        assertEquals(WorldCommitCore.Outcome.APPLIED_BREAK,core.tick(world,201).getFirst().result());
        assertEquals(1,world.breakCalls);assertEquals(1,world.drops);
        assertEquals(1,journal.revision(k.dimension(),k.x(),k.y(),k.z()),
                "PDD's own vanilla break must persist re-placement incarnation");
        assertNull(journal.get(k));
        var reboot=new WorldCommitCore(data.open(),DamageModel.Settings.defaults(),64);
        assertEquals(WorldCommitCore.Outcome.DUPLICATE,
                reboot.submit(intent("single-break",k,5000)).result());
        assertEquals(1,world.drops);
    }
    @Test void restartDeferredApplyIdIsNotRescheduledAsNewBreak()throws Exception{
        var data=area();var journal=data.open();var k=key(6);
        var first=new WorldCommitCore(journal,DamageModel.Settings.defaults(),64);
        assertEquals(WorldCommitCore.Outcome.DEFERRED,first.submit(intent("deferred",k,1000)).result());
        var unloaded=new Fake();unloaded.loaded=false;
        assertEquals(WorldCommitCore.Outcome.DEFERRED,first.tick(unloaded,201).getFirst().result());
        var boot=new WorldCommitCore(data.open(),DamageModel.Settings.defaults(),64);
        assertEquals(WorldCommitCore.Outcome.DUPLICATE,boot.submit(intent("deferred",k,1000)).result());
        assertEquals(0,boot.queued());
        assertEquals(0,unloaded.breakCalls);
        var after=new Fake();assertEquals(1,boot.quarantineInterrupted(after,202));
        assertEquals(WorldReceiptJournal.Status.ABORTED,
                data.open().receipt("deferred").status());
        assertEquals("RESTART_DEFERRED_NOT_EXECUTED_INTENT_NOT_PERSISTED",
                data.open().receipt("deferred").reason());
    }
    @Test void restartDeferredCanCloseWithoutLoadingChunkOrChangingWorld()throws Exception{
        var data=area();var k=key(7);
        var original=new WorldCommitCore(data.open(),DamageModel.Settings.defaults(),64);
        original.submit(intent("lost-q",k,5000));
        var rebootJournal=data.open();
        var reboot=new WorldCommitCore(rebootJournal,DamageModel.Settings.defaults(),64);
        var inaccessible=new Fake();inaccessible.loaded=false;
        assertEquals(1,reboot.quarantineInterrupted(inaccessible,203));
        assertEquals(WorldReceiptJournal.Status.ABORTED,rebootJournal.receipt("lost-q").status());
        assertEquals(0,inaccessible.breakCalls);
        assertEquals(0,rebootJournal.size());
        assertFalse(rebootJournal.recoveredUncertain("lost-q"));
        assertEquals(WorldCommitCore.Outcome.DUPLICATE,
                reboot.submit(intent("lost-q",k,5000)).result());
    }
    @Test void restartPreparedRemainsUncertainUntilLoadedThenQuarantined()throws Exception{
        var data=area();var k=key(8);
        data.open().transition(new WorldReceiptJournal.Receipt("possibly-broken",k,
                WorldReceiptJournal.Status.PREPARED,"PREPARED_DURABLE",201),null);
        var journal=data.open();
        var reboot=new WorldCommitCore(journal,DamageModel.Settings.defaults(),64);
        var world=new Fake();world.loaded=false;
        assertEquals(0,reboot.quarantineInterrupted(world,202));
        assertEquals(WorldReceiptJournal.Status.PREPARED,journal.receipt("possibly-broken").status());
        world.loaded=true;
        assertEquals(1,reboot.quarantineInterrupted(world,203));
        assertEquals(WorldReceiptJournal.Status.PARTIAL_QUARANTINED,
                data.open().receipt("possibly-broken").status());
        assertTrue(data.open().receipt("possibly-broken").reason().contains("MUTATION_UNCERTAIN_PRESENT"));
        assertEquals(0,world.breakCalls);
        assertEquals(WorldCommitCore.Outcome.DUPLICATE,
                reboot.submit(intent("possibly-broken",k,5000)).result());
    }
    @Test void oldMoreFixFalseQuarantineIsRecognizedWithoutReopeningItsApplyId()throws Exception{
        var data=area();var key=key(11);
        var journal=data.open();
        journal.transition(new WorldReceiptJournal.Receipt("old-deferred",key,
                WorldReceiptJournal.Status.DEFERRED,"QUEUED_IN_CURRENT_RUN",200),null);
        journal.transition(new WorldReceiptJournal.Receipt("old-deferred",key,
                WorldReceiptJournal.Status.PARTIAL_QUARANTINED,
                "RESTART_UNCERTAIN_DEFERRED_PRESENT",201),null);
        journal.transition(new WorldReceiptJournal.Receipt("old-prepared",key,
                WorldReceiptJournal.Status.PREPARED,"PREPARED_DURABLE",201),null);
        journal.transition(new WorldReceiptJournal.Receipt("old-prepared",key,
                WorldReceiptJournal.Status.PARTIAL_QUARANTINED,
                "RESTART_UNCERTAIN_PREPARED_PRESENT",202),null);
        var reboot=data.open();
        assertEquals(2,reboot.quarantinedSnapshot().size());
        assertTrue(WorldPersistentJournal.isLegacyUnexecutedDeferred(reboot.receipt("old-deferred")));
        assertFalse(WorldPersistentJournal.isLegacyUnexecutedDeferred(reboot.receipt("old-prepared")));
        assertEquals(WorldCommitCore.Outcome.DUPLICATE,
                new WorldCommitCore(reboot,DamageModel.Settings.defaults(),64)
                    .submit(intent("old-deferred",key,5000)).result());
    }
    @Test void loadedChunkGateAndDamageSurviveRestartWithoutWorldScan()throws Exception{
        var data=area();var journal=data.open();var core=new WorldCommitCore(journal,DamageModel.Settings.defaults(),64);
        var block=key(9);var world=new Fake();world.loaded=false;
        assertEquals(WorldCommitCore.Outcome.DEFERRED,core.submit(intent("D",block,1.0)).result());
        assertEquals("CHUNK_NOT_LOADED",core.tick(world,201).getFirst().reason());
        assertEquals(0,journal.size());assertEquals(1,core.queued());
        world.loaded=true;
        assertEquals(WorldCommitCore.Outcome.APPLIED_DAMAGE,core.tick(world,202).getFirst().result());
        var before=journal.get(block);
        assertTrue(before.damage()>0 || before.remainder()>0);
        assertEquals(before,data.open().get(block));
        assertEquals(WorldCommitCore.Outcome.DUPLICATE,
                new WorldCommitCore(data.open(),DamageModel.Settings.defaults(),64)
                        .submit(intent("D",block,1.0)).result());
    }
    @Test void unobservedForeignStateAtSamePositionQuarantinesWithoutGhostDamage()throws Exception{
        var data=area();var journal=data.open();var k=key(12);
        journal.transition(new WorldReceiptJournal.Receipt("old",k,
                WorldReceiptJournal.Status.APPLIED,"DAMAGE",200),
                new WorldReceiptJournal.State(k,"0-real-fingerprint",50,30,1,200));
        var different=new SparseStateKey(DIM,"WORLD",DIM,1,12,64,5,"minecraft:copper_block");
        var hit=intent("new",different,2500);
        var world=new Fake();var core=new WorldCommitCore(journal,DamageModel.Settings.defaults(),64);
        core.submit(hit);
        var outcome=core.tick(world,201).getFirst();
        assertEquals(WorldCommitCore.Outcome.ABORTED,outcome.result());
        assertEquals("STALE_POSITION_STATE_QUARANTINED",outcome.reason());
        assertEquals(0,world.breakCalls);
        assertEquals(50,journal.get(k).damage()); // ambiguous old damage held out of new owner
        assertNull(journal.get(different));
    }
    @Test void vetoStillClosesReceiptAndDoesNotBreakOrChangeDamage()throws Exception{
        var data=area();var journal=data.open();var k=key(15);
        var core=new WorldCommitCore(journal,DamageModel.Settings.defaults(),64);
        var world=new Fake();world.authorized=false;
        core.submit(intent("protected",k,5000));
        assertEquals(WorldCommitCore.Outcome.ABORTED,core.tick(world,201).getFirst().result());
        assertEquals(0,world.breakCalls);assertEquals(0,world.drops);
        assertEquals(WorldReceiptJournal.Status.ABORTED,data.open().receipt("protected").status());
    }
}
