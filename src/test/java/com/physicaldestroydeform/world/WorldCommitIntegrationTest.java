package com.physicaldestroydeform.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.MaterialProperties;
import com.physicaldestroydeform.state.SparseStateKey;
import java.nio.file.*;

/** Pure JUnit contract. Actual NeoForge block events/drops require in-game verification. */
final class WorldCommitIntegrationTest {
    private static final String DIM="minecraft:overworld";
    private static final MaterialProperties STONE=new MaterialProperties(59,91,10,9,false);
    private static SparseStateKey at(int x) {return new SparseStateKey(DIM,"WORLD",DIM,1,x,72,0,"minecraft:stone|Block{minecraft:stone}");}
    private static WorldCommitCore.Intent intent(String id,SparseStateKey key,double q) {
        return new WorldCommitCore.Intent(id,key,"0-fingerprint-sha",q,STONE,1,200,true);
    }
    private static class FakeWorld implements WorldCommitCore.WorldGateway {
        boolean loaded=true,air,permission=true,entity,unsafe,unbreakable,thread=true,altered;
        int breaks,drops;
        @Override public boolean isServerThread(){return thread;}
        @Override public WorldCommitCore.Observation readIfLoaded(SparseStateKey key){
            if(!loaded)return null;
            return new WorldCommitCore.Observation(DIM,key.fingerprint(),1,altered?"1-new":"0-fingerprint-sha",
                    true,air,entity&&!air,unsafe&&!air,unbreakable&&!air,false);
        }
        @Override public WorldCommitCore.Permission canDamage(SparseStateKey key,WorldCommitCore.Observation read) {
            return permission?WorldCommitCore.Permission.ALLOW:WorldCommitCore.Permission.DENY;
        }
        @Override public WorldCommitCore.Permission canBreak(SparseStateKey key,WorldCommitCore.Observation read) {
            return permission?WorldCommitCore.Permission.ALLOW:WorldCommitCore.Permission.DENY;
        }
        @Override public boolean breakVanillaOnce(SparseStateKey key) {
            breaks++;if(air)return false;air=true;drops++;return true;
        }
    }
    @Test void accumulatedStoneFixedDamageSurvivesReloadAndBreakDropsOnce() throws Exception {
        Path path=Files.createTempFile("pdd-wal-junit-",".bin");Files.delete(path);
        var journal=new WorldReceiptJournal(path);var engine=new WorldCommitCore(journal,DamageModel.Settings.defaults(),64);
        var world=new FakeWorld();var key=at(2);
        engine.submit(intent("collision-A",key,0.75));
        assertEquals(WorldCommitCore.Outcome.APPLIED_DAMAGE,engine.tick(world,201).getFirst().result());
        long a=journal.get(key).damage()*256L+journal.get(key).remainder();
        assertTrue(a>0);
        engine.submit(intent("collision-B",key,0.75));
        engine.tick(world,202);
        long b=journal.get(key).damage()*256L+journal.get(key).remainder();
        assertTrue(b>a,"new collision must accumulate");
        assertEquals(b,new WorldReceiptJournal(path).get(key).damage()*256L + new WorldReceiptJournal(path).get(key).remainder());
        engine.submit(intent("collision-C",key,2000));
        assertEquals(WorldCommitCore.Outcome.APPLIED_BREAK,engine.tick(world,203).getFirst().result());
        assertEquals(1,world.drops);assertEquals(1,world.breaks);
        assertNull(new WorldReceiptJournal(path).get(key));
        assertEquals(WorldCommitCore.Outcome.DUPLICATE,engine.submit(intent("collision-C",key,2000)).result());
    }
    @Test void chunkProtectionEntityAndReplacementReject() throws Exception {
        for(int i=0;i<4;i++) {
            var engine=new WorldCommitCore(new WorldReceiptJournal(Files.createTempDirectory("pdd-veto-").resolve("world.wal")),
                    DamageModel.Settings.defaults(),64);
            var world=new FakeWorld();world.loaded=i!=0;world.permission=i!=1;
            world.entity=i==2;world.altered=i==3;
            engine.submit(intent("veto-"+i,at(10+i),1000));
            var outcome=engine.tick(world,201).getFirst().result();
            assertEquals(i==0?WorldCommitCore.Outcome.DEFERRED:WorldCommitCore.Outcome.ABORTED,outcome);
            assertEquals(0,world.drops);
        }
    }

    @Test void ordinaryWorldBlockIdsBreakButImmuneAndUnsafeBlocksDoNot() throws Exception {
        var stone=new MaterialProperties(59,91,10,9,false);
        // The block's namespace/ID must not be a hardcoded veto (slime, netherite, mod block).
        String[] ids={"minecraft:slime_block", "minecraft:netherite_block", "example:metal_block"};
        for(int i=0;i<ids.length;i++) {
            var engine=new WorldCommitCore(new WorldReceiptJournal(Files.createTempDirectory("pdd-open-ids-").resolve("world.wal")),
                    DamageModel.Settings.defaults(),64);
            var fake=new FakeWorld();
            var key=new SparseStateKey(DIM,"WORLD",DIM,1,150+i,72,0,ids[i]+"|Block{"+ids[i]+"}");
            var hit=new WorldCommitCore.Intent("open-id-"+i,key,"0-fingerprint-sha",6000,stone,1,200,true);
            engine.submit(hit);
            assertEquals(WorldCommitCore.Outcome.APPLIED_BREAK,engine.tick(fake,201).getFirst().result(),ids[i]);
            assertEquals(1,fake.breaks);assertEquals(1,fake.drops);
            assertEquals(WorldCommitCore.Outcome.DUPLICATE,engine.submit(hit).result());
        }
        // Definition immunity, intrinsic vanilla hardness, BE and linked blocks are separate vetoes.
        var immuneMaterial=new MaterialProperties(255,255,0,0,true);
        for(int i=0;i<4;i++) {
            var engine=new WorldCommitCore(new WorldReceiptJournal(Files.createTempDirectory("pdd-safety-").resolve("world.wal")),
                    DamageModel.Settings.defaults(),64);
            var fake=new FakeWorld();fake.entity=i==2;fake.unsafe=i==3;fake.unbreakable=i==1;
            var key=at(160+i);
            var hit=new WorldCommitCore.Intent("safety-"+i,key,"0-fingerprint-sha",6000,i==0?immuneMaterial:stone,1,200,true);
            engine.submit(hit);
            var decision=engine.tick(fake,201).getFirst();
            assertEquals(WorldCommitCore.Outcome.ABORTED,decision.result());
            assertEquals(i<2?"UNBREAKABLE":"BE_OR_MULTIBLOCK",decision.reason());
            assertEquals(0,fake.breaks);assertEquals(0,fake.drops);
        }
    }
    @Test void damageOnlyAuthorizationMutationIsRejected() throws Exception {
        var journal=new WorldReceiptJournal(Files.createTempDirectory("pdd-event-mutation-").resolve("world.wal"));
        var engine=new WorldCommitCore(journal,DamageModel.Settings.defaults(),64);
        var world=new FakeWorld() {
            @Override public WorldCommitCore.Permission canDamage(SparseStateKey key,
                    WorldCommitCore.Observation expected) {
                altered=true;
                return WorldCommitCore.Permission.ALLOW;
            }
        };
        var key=at(44);
        engine.submit(intent("damage-event-replace",key,0.75));
        assertEquals(WorldCommitCore.Outcome.ABORTED,engine.tick(world,201).getFirst().result());
        assertNull(journal.get(key));
        assertEquals(0,world.breaks);
    }
    @Test void restartPreparedIsQuarantinedAndNoDrops() throws Exception {
        Path file=Files.createTempDirectory("pdd-interrupt-").resolve("world.wal");
        var journal=new WorldReceiptJournal(file);var key=at(8);
        journal.transition(new WorldReceiptJournal.Receipt("interrupt",key,
                WorldReceiptJournal.Status.PREPARED,"POWER_LOSS",201),null);
        var reboot=new WorldCommitCore(new WorldReceiptJournal(file),DamageModel.Settings.defaults(),64);
        var world=new FakeWorld();world.air=true;
        assertEquals(1,reboot.quarantineInterrupted(world,202));
        assertEquals(WorldCommitCore.Outcome.DUPLICATE,reboot.submit(intent("interrupt",key,1000)).result());
        assertEquals(0,world.drops);
    }
    @Test void walCrcAndApplyIdAliasingAreRejected() throws Exception {
        Path file=Files.createTempDirectory("pdd-bad-wal-").resolve("world.wal");
        var journal=new WorldReceiptJournal(file);
        journal.transition(new WorldReceiptJournal.Receipt("A",at(1),
                WorldReceiptJournal.Status.APPLIED,"OK",100),null);
        assertThrows(IllegalArgumentException.class,()->journal.transition(new WorldReceiptJournal.Receipt("A",at(2),
                WorldReceiptJournal.Status.APPLIED,"ALIAS",101),null));
        var bytes=Files.readAllBytes(file);bytes[bytes.length-1]^=1;
        Path broken=file.resolveSibling("damaged.wal");Files.write(broken,bytes);
        assertThrows(java.io.IOException.class,()->new WorldReceiptJournal(broken));
    }
    @Test void perTickBreakCapDefersAndLaterProcesses() throws Exception {
        var journal=new WorldReceiptJournal(Files.createTempDirectory("pdd-64-").resolve("world.wal"));
        var engine=new WorldCommitCore(journal,DamageModel.Settings.defaults(),1);
        engine.submit(intent("A",at(4),1500));engine.submit(intent("B",at(5),1500));
        assertEquals(1,engine.tick(new FakeWorld(),201).stream()
                .filter(x->x.result()==WorldCommitCore.Outcome.APPLIED_BREAK).count());
        assertEquals(1,engine.queued());
        assertTrue(engine.tick(new FakeWorld(),202).stream()
                .anyMatch(x->x.result()==WorldCommitCore.Outcome.APPLIED_BREAK));
    }
    @Test void damageOnlyDoesNotRequestBreakAuthorizationAndBreakVetoIsFinal() throws Exception {
        var journal=new WorldReceiptJournal(Files.createTempDirectory("pdd-break-event-").resolve("world.wal"));
        var engine=new WorldCommitCore(journal,DamageModel.Settings.defaults(),64);
        var world=new FakeWorld() {
            int breakPermissionCalls;
            @Override public WorldCommitCore.Permission canBreak(SparseStateKey key,
                    WorldCommitCore.Observation expected) {
                breakPermissionCalls++;
                return WorldCommitCore.Permission.DENY; // simulate canceled BreakEvent
            }
        };
        var key=at(49);
        engine.submit(intent("damage-only",key,0.75));
        assertEquals(WorldCommitCore.Outcome.APPLIED_DAMAGE,engine.tick(world,201).getFirst().result());
        assertEquals(0,world.breakPermissionCalls,"damage-only plan must not emit BreakEvent");
        assertNotNull(journal.get(key));
        engine.submit(intent("break-veto",key,2000));
        assertEquals(WorldCommitCore.Outcome.ABORTED,engine.tick(world,202).getFirst().result());
        assertNotNull(journal.get(key),"failed break must not clear already stored damage");
        assertEquals(0,world.breaks);
        assertEquals(0,world.drops);
        assertEquals(1,world.breakPermissionCalls,"break authorization must be evaluated once");
    }
    @Test void baselineStandaloneRegressionsRemainApplicable() throws Exception {
        WorldCommitCoreSelfTest.main(new String[0]);
        WorldIntentMapperSelfTest.main(new String[0]);
    }
}
