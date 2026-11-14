package com.physicaldestroydeform.plot;

import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.MaterialProperties;
import com.physicaldestroydeform.state.SparseStateKey;
import com.physicaldestroydeform.world.WorldCommitCore;
import com.physicaldestroydeform.world.WorldReceiptJournal;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 0.5.1-safeFix contract: no PREPARED receipt while the Plot is splitting. */
class PlotSafeBreakGateTest {
    @TempDir Path temp;
    private static final MaterialProperties DIRT=new MaterialProperties(59,90,10,0,false);
    private static final String A="123e4567-e89b-12d3-a456-426614174000";
    private static final String B="123e4567-e89b-12d3-a456-426614174001";
    private static WorldCommitCore.Intent hit(String id,String owner,int x){
        var key=PlotKeyCodec.storage(new SparseStateKey("minecraft:overworld","PLOT",owner,1,
                x,64,0,"minecraft:dirt|Block{minecraft:dirt}"));
        return new WorldCommitCore.Intent(id,key,"i1",10000,DIRT,1,20,true);
    }
    private static final class Backend implements WorldCommitCore.WorldGateway {
        final Set<SparseStateKey> air=new HashSet<>();
        final Map<String,Integer> lastBreak=new HashMap<>();
        boolean heatMapStable=true,becomesUnstableDuringBreakEvent;
        int tick,breakCalls,permissionCalls;
        @Override public boolean isServerThread(){return true;}
        @Override public WorldCommitCore.Observation readIfLoaded(SparseStateKey key){
            boolean removed=air.contains(key);
            return new WorldCommitCore.Observation(key.dimension(),removed?"air":key.fingerprint(),1,
                    "i1",true,removed,false,false,false,false);
        }
        @Override public WorldCommitCore.Permission canDamage(SparseStateKey key,WorldCommitCore.Observation expected){
            permissionCalls++;return WorldCommitCore.Permission.ALLOW;
        }
        @Override public WorldCommitCore.Permission canBreak(SparseStateKey key,WorldCommitCore.Observation expected){
            permissionCalls++;
            if(becomesUnstableDuringBreakEvent){heatMapStable=false;becomesUnstableDuringBreakEvent=false;}
            return WorldCommitCore.Permission.ALLOW;
        }
        @Override public String deferBreakReason(SparseStateKey key){
            if(!heatMapStable)return "SABLE_HEATMAP_PENDING_SPLIT";
            String owner=PlotKeyCodec.real(key).ownerId();
            Integer prior=lastBreak.get(owner);
            return prior!=null && tick-prior<3 ? "PLOT_BREAK_SPACING" : null;
        }
        @Override public boolean breakVanillaOnce(SparseStateKey key){
            lastBreak.put(PlotKeyCodec.real(key).ownerId(),tick);
            breakCalls++;return air.add(key);
        }
    }
    @Test void holdsSameOwnerWithoutPreparingOrDuplicatingThenExecutesAfterStableHeatMap() throws Exception {
        var journal=new WorldReceiptJournal(temp.resolve("plot-receipts.bin"));
        var core=new WorldCommitCore(journal,DamageModel.Settings.defaults(),32);
        var backend=new Backend();
        core.submit(hit("a1",A,1));core.submit(hit("a2",A,2));core.submit(hit("b1",B,3));
        backend.tick=30;
        var first=core.tick(backend,backend.tick,32);
        assertEquals(3,first.size());
        assertEquals(2,backend.breakCalls); // one per owner even with 32-operation budget
        assertEquals(WorldCommitCore.Outcome.DEFERRED,first.get(1).result());
        assertEquals("PLOT_BREAK_SPACING",first.get(1).reason());
        assertEquals(1,core.queued());
        assertEquals(WorldReceiptJournal.Status.DEFERRED,journal.receipt("a2").status());
        assertEquals(WorldCommitCore.Outcome.DUPLICATE,core.submit(hit("a2",A,2)).result());
        backend.tick=31; backend.heatMapStable=false;
        var held=core.tick(backend,backend.tick,32);
        assertEquals(1,held.size()); // no busy recirculation when the queue has only one item
        assertEquals("SABLE_HEATMAP_PENDING_SPLIT",held.getFirst().reason());
        assertEquals(2,backend.breakCalls);
        assertEquals(WorldReceiptJournal.Status.DEFERRED,journal.receipt("a2").status());
        backend.tick=32; backend.heatMapStable=true;
        assertEquals("PLOT_BREAK_SPACING",core.tick(backend,backend.tick,32).getFirst().reason());
        assertEquals(2,backend.breakCalls);
        backend.tick=33;
        var success=core.tick(backend,backend.tick,32).getFirst();
        assertEquals(WorldCommitCore.Outcome.APPLIED_BREAK,success.result());
        assertEquals(3,backend.breakCalls);
        assertEquals(WorldReceiptJournal.Status.APPLIED,journal.receipt("a2").status());
        assertEquals(0,core.queued());
    }
    @Test void unfinishedSplitAlwaysExpiresWithoutAPreparedOrNativeBreak() throws Exception {
        var journal=new WorldReceiptJournal(temp.resolve("split-receipts.bin"));
        var core=new WorldCommitCore(journal,DamageModel.Settings.defaults(),32);
        var backend=new Backend();backend.heatMapStable=false;
        core.submit(hit("split",A,1));
        backend.tick=21;
        assertEquals("SABLE_HEATMAP_PENDING_SPLIT",core.tick(backend,backend.tick,32).getFirst().reason());
        backend.tick=20+(int)WorldCommitCore.MAX_DEFER_TICKS+1;
        assertEquals("DEFER_TTL_EXPIRED",core.tick(backend,backend.tick,32).getFirst().reason());
        assertEquals(0,backend.breakCalls);
        assertEquals(WorldReceiptJournal.Status.ABORTED,journal.receipt("split").status());
        assertEquals(WorldCommitCore.Outcome.DUPLICATE,core.submit(hit("split",A,1)).result());
    }
    @Test void splitTriggeredByProtectionEventDefersBeforePreparedWithoutCallingSable() throws Exception {
        var journal=new WorldReceiptJournal(temp.resolve("event-receipts.bin"));
        var core=new WorldCommitCore(journal,DamageModel.Settings.defaults(),32);
        var backend=new Backend();backend.becomesUnstableDuringBreakEvent=true;
        core.submit(hit("event",A,1));backend.tick=23;
        var first=core.tick(backend,backend.tick,32).getFirst();
        assertEquals(WorldCommitCore.Outcome.DEFERRED,first.result());
        assertEquals("AFTER_EVENTS_PLOT_GATE_SABLE_HEATMAP_PENDING_SPLIT",first.reason());
        assertEquals(0,backend.breakCalls);
        assertEquals(1,core.queued());
        assertEquals(WorldReceiptJournal.Status.DEFERRED,journal.receipt("event").status());
        backend.heatMapStable=true;backend.tick=24;
        assertEquals(WorldCommitCore.Outcome.APPLIED_BREAK,core.tick(backend,backend.tick,32).getFirst().result());
        assertEquals(1,backend.breakCalls);
    }
    @Test void deferredOwnerCanBeQuarantinedWithSameDurableApplyId() throws Exception {
        var journal=new WorldReceiptJournal(temp.resolve("orphan-receipts.bin"));
        var core=new WorldCommitCore(journal,DamageModel.Settings.defaults(),32);
        var backend=new Backend();backend.heatMapStable=false;
        core.submit(hit("old-owner",A,1));backend.tick=22;
        core.tick(backend,backend.tick,32);
        assertEquals(1,core.abortMatching(i->PlotKeyCodec.real(i.key()).ownerId().equals(A),
                "ORPHAN_QUARANTINE_SPLIT",23));
        assertEquals(0,core.queued());
        assertEquals(0,backend.breakCalls);
        assertEquals(WorldReceiptJournal.Status.ABORTED,journal.receipt("old-owner").status());
    }
}
