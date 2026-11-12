package com.physicaldestroydeform.plot;

import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.MaterialProperties;
import com.physicaldestroydeform.state.SparseStateKey;
import com.physicaldestroydeform.world.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Persistence/receipt implementation with an isolated FAKE Plot backend (not an in-game pass). */
class PlotCommitCoreTest {
    @TempDir Path temp;
    private static final String DIM="minecraft:overworld";
    private static final String OWNER="123e4567-e89b-12d3-a456-426614174000";
    private static final MaterialProperties MATERIAL=new MaterialProperties(59,90,10,0,false);
    private static SparseStateKey plot(String id){
        return new SparseStateKey(DIM,"PLOT",id,1,1,64,3,"minecraft:stone|Block{minecraft:stone}");
    }
    private static WorldCommitCore.Intent intent(String id,SparseStateKey real,double q){
        return new WorldCommitCore.Intent(id,PlotKeyCodec.storage(real),"incarnation-1",q,MATERIAL,1,20,true);
    }
    private static class FakePlot implements WorldCommitCore.WorldGateway {
        boolean air,be,claims,loaded=true;int breaks,drops;
        @Override public boolean isServerThread(){return true;}
        @Override public WorldCommitCore.Observation readIfLoaded(SparseStateKey virtual){
            if(!loaded)return null;
            return new WorldCommitCore.Observation(virtual.dimension(),
                    air?"minecraft:air|Block{minecraft:air}":virtual.fingerprint(),1,"incarnation-1",
                    true,air,be,false,false,false);
        }
        @Override public WorldCommitCore.Permission canDamage(SparseStateKey key,WorldCommitCore.Observation obs){
            return claims?WorldCommitCore.Permission.DENY:WorldCommitCore.Permission.ALLOW;
        }
        @Override public WorldCommitCore.Permission canBreak(SparseStateKey key,WorldCommitCore.Observation obs){
            return claims?WorldCommitCore.Permission.DENY:WorldCommitCore.Permission.ALLOW;
        }
        @Override public boolean breakVanillaOnce(SparseStateKey key){
            if(air)return false;air=true;breaks++;drops++;return true;
        }
    }
    @Test void plotStorageOwnerIsolationAndAtomicDamageBreak() throws Exception {
        var journal=new WorldPersistentJournal(temp.resolve("plot"),temp.resolve("none.wal"));
        var core=new WorldCommitCore(journal,DamageModel.Settings.defaults(),32);
        var a=plot(OWNER);var b=plot("123e4567-e89b-12d3-a456-426614174001");
        var fake=new FakePlot();
        core.submit(intent("damage-a",a,2));
        assertEquals(WorldCommitCore.Outcome.APPLIED_DAMAGE,core.tick(fake,21).getFirst().result());
        assertNotNull(journal.get(PlotKeyCodec.storage(a)));
        assertNull(journal.get(PlotKeyCodec.storage(b)));
        core.submit(intent("break-a",a,10000));
        assertEquals(WorldCommitCore.Outcome.APPLIED_BREAK,core.tick(fake,22).getFirst().result());
        assertEquals(1,fake.breaks);assertEquals(1,fake.drops);
        assertNull(journal.get(PlotKeyCodec.storage(a)));
        assertEquals(WorldCommitCore.Outcome.DUPLICATE,core.submit(intent("break-a",a,10000)).result());
        var restored=new WorldPersistentJournal(temp.resolve("plot"),temp.resolve("none.wal"));
        assertNotNull(restored.receipt("break-a"));
    }
    @Test void BEClaimsAndOldOwnerAbortNeverMakeFalseSuccess() throws Exception {
        var journal=new WorldPersistentJournal(temp.resolve("plot2"),temp.resolve("none.wal"));
        var core=new WorldCommitCore(journal,DamageModel.Settings.defaults(),32);
        var key=plot(OWNER);
        var be=new FakePlot();be.be=true;
        core.submit(intent("BE",key,10000));
        assertEquals("BE_OR_MULTIBLOCK",core.tick(be,22).getFirst().reason());
        assertEquals(0,be.breaks);
        var claims=new FakePlot();claims.claims=true;
        core.submit(intent("CLAIM",key,10000));
        assertEquals(WorldCommitCore.Outcome.ABORTED,core.tick(claims,22).getFirst().result());
        core.submit(intent("SPLIT",key,10000));
        assertEquals(1,core.abortMatching(i->PlotKeyCodec.real(i.key()).ownerId().equals(OWNER),
                "ORPHAN_QUARANTINE_SPLIT",22));
        assertEquals(0,core.queued());
        assertEquals(WorldReceiptJournal.Status.ABORTED,journal.receipt("SPLIT").status());
        assertEquals(WorldCommitCore.Outcome.DUPLICATE,core.submit(intent("SPLIT",key,10000)).result());
        assertTrue(core.tick(new FakePlot(),23).isEmpty());
    }
    @Test void crashPreparedIsNeverAppliedSecondTime() throws Exception {
        var journal=new WorldPersistentJournal(temp.resolve("plot3"),temp.resolve("none.wal"));
        var intent=intent("crash",plot(OWNER),10000);
        journal.transition(new WorldReceiptJournal.Receipt("crash",intent.key(),
                WorldReceiptJournal.Status.PREPARED,"PREPARED_DURABLE",22),null);
        var restored=new WorldPersistentJournal(temp.resolve("plot3"),temp.resolve("none.wal"));
        var core=new WorldCommitCore(restored,DamageModel.Settings.defaults(),32);
        var fake=new FakePlot();
        assertEquals(WorldCommitCore.Outcome.QUARANTINED,
                core.reconcileRestart(restored.uncertain().getFirst(),fake.readIfLoaded(intent.key()),23).result());
        assertEquals(0,fake.breaks);
        assertEquals(WorldCommitCore.Outcome.DUPLICATE,core.submit(intent).result());
    }
    @Test void capCountsAllPlotMutationsNotOnlyBreaks() throws Exception {
        var journal=new WorldPersistentJournal(temp.resolve("plot-cap"),temp.resolve("none.wal"));
        var core=new WorldCommitCore(journal,DamageModel.Settings.defaults(),32);
        var key=plot(OWNER);
        for(int i=0;i<33;i++)core.submit(intent("CAP-"+i,key,0.25));
        var fake=new FakePlot();
        var first=core.tick(fake,21,32);
        assertEquals(32,first.size());
        assertEquals(1,core.queued());
        var last=core.tick(fake,22,32);
        assertEquals(1,last.size());
        assertEquals(0,core.queued());
    }
    @Test void durableOrphanMarkerSurvivesCrashBeforeCleanup() throws Exception {
        var root=temp.resolve("plot-orphan");
        var journal=new WorldPersistentJournal(root,temp.resolve("none.wal"));
        var original=plot(OWNER);
        var virtual=PlotKeyCodec.storage(original);
        var source=new WorldReceiptJournal.State(virtual,"incarnation-1",12,9,1,21);
        journal.transition(new WorldReceiptJournal.Receipt("prior",virtual,
                WorldReceiptJournal.Status.APPLIED,"FIXED_POINT_APPLIED",21),source);
        var marker=PlotKeyCodec.storage(new SparseStateKey(original.dimension(),"PLOT",original.ownerId(),
                original.ownerGeneration(),0,0,0,"PDD:OWNER_ORPHAN"));
        journal.transition(new WorldReceiptJournal.Receipt("ORPHAN:1:parent",marker,
                WorldReceiptJournal.Status.PARTIAL_QUARANTINED,"ORPHAN_QUARANTINE_SPLIT",22),null);
        // Simulate crash here: damage still physically stored, but owner is marked unsafe.
        var restored=new WorldPersistentJournal(root,temp.resolve("none.wal"));
        assertEquals(1,restored.quarantinedSnapshot().size());
        assertNotNull(restored.get(virtual));
        assertEquals(original.ownerId(),PlotKeyCodec.real(
                restored.quarantinedSnapshot().getFirst().key()).ownerId());
        restored.discardAtPosition(virtual.dimension(),virtual.x(),virtual.y(),virtual.z());
        assertNull(restored.get(virtual));
        assertEquals(1,new WorldPersistentJournal(root,temp.resolve("none.wal"))
                .quarantinedSnapshot().size());
    }

}
