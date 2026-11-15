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

/** No PREPARED receipt while the Plot is splitting or lacks a fresh tick witness. */
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
        final Map<String,PlotBreakPacing.Attempt> lastBreak=new HashMap<>();
        boolean heatMapStable=true,becomesUnstableDuringBreakEvent,staleWitness,failBreak;
        int tick,breakCalls,permissionCalls,spacing=1;
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
            return PlotBreakPacing.holdReason(tick,staleWitness?tick-1:tick,lastBreak.get(owner),spacing);
        }
        @Override public boolean breakVanillaOnce(SparseStateKey key){
            lastBreak.put(PlotKeyCodec.real(key).ownerId(),new PlotBreakPacing.Attempt(tick,false));
            breakCalls++;return !failBreak && air.add(key);
        }
        @Override public void onBreakReadback(SparseStateKey key,boolean apiResult,
                WorldCommitCore.Observation reread,WorldCommitCore.BreakReadback status) {
            if(apiResult && status==WorldCommitCore.BreakReadback.CONFIRMED_AIR)
                lastBreak.put(PlotKeyCodec.real(key).ownerId(),new PlotBreakPacing.Attempt(tick,true));
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
        backend.tick=32; backend.heatMapStable=true;backend.staleWitness=true;
        assertEquals("SABLE_HEATMAP_TICK_UNCONFIRMED",core.tick(backend,backend.tick,32).getFirst().reason());
        assertEquals(2,backend.breakCalls);
        backend.tick=33;backend.staleWitness=false;
        var success=core.tick(backend,backend.tick,32).getFirst();
        assertEquals(WorldCommitCore.Outcome.APPLIED_BREAK,success.result());
        assertEquals(3,backend.breakCalls);
        assertEquals(WorldReceiptJournal.Status.APPLIED,journal.receipt("a2").status());
        assertEquals(0,core.queued());
    }
    @Test void stableOwnerBreaksOnceEveryTickAndLegacySpacingStillWorks() throws Exception {
        var journal=new WorldReceiptJournal(temp.resolve("fast-receipts.bin"));
        var core=new WorldCommitCore(journal,DamageModel.Settings.defaults(),32);
        var backend=new Backend();
        for(int i=0;i<4;i++)core.submit(hit("fast"+i,A,i));
        for(int tick=30;tick<=33;tick++){
            backend.tick=tick;
            core.tick(backend,tick,32);
            assertEquals(tick-29,backend.breakCalls);
            assertEquals(33-tick,core.queued());
        }
        assertEquals(1,PlotBreakPacing.spacing(0));
        assertEquals(40,PlotBreakPacing.spacing(41));
        var prior=new PlotBreakPacing.Attempt(30,true);
        assertEquals("PLOT_BREAK_SPACING",PlotBreakPacing.holdReason(31,31,prior,3));
        assertEquals("PLOT_BREAK_SPACING",PlotBreakPacing.holdReason(32,32,prior,3));
        assertNull(PlotBreakPacing.holdReason(33,33,prior,3));
        assertEquals("SABLE_HEATMAP_TICK_UNCONFIRMED",PlotBreakPacing.holdReason(30,-1,null,1));
    }
    @Test void failedNativeAttemptStillReservesSameTickAndIsNotRetried() throws Exception {
        var journal=new WorldReceiptJournal(temp.resolve("failed-receipts.bin"));
        var core=new WorldCommitCore(journal,DamageModel.Settings.defaults(),32);
        var backend=new Backend();backend.failBreak=true;backend.tick=30;
        core.submit(hit("failed",A,1));core.submit(hit("next",A,2));
        var results=core.tick(backend,30,32);
        assertEquals(WorldCommitCore.Outcome.QUARANTINED,results.getFirst().result());
        assertEquals("PLOT_BREAK_SPACING",results.get(1).reason());
        assertEquals(1,backend.breakCalls);
        assertEquals(WorldReceiptJournal.Status.PARTIAL_QUARANTINED,journal.receipt("failed").status());
        backend.failBreak=false;
        for(int tick=31;tick<=32;tick++){
            backend.tick=tick;
            assertEquals("PLOT_BREAK_SPACING",core.tick(backend,tick,32).getFirst().reason());
            assertEquals(1,backend.breakCalls);
        }
        backend.tick=33;
        assertEquals(WorldCommitCore.Outcome.APPLIED_BREAK,core.tick(backend,33,32).getFirst().result());
        assertEquals(2,backend.breakCalls);
        assertEquals(WorldCommitCore.Outcome.DUPLICATE,core.submit(hit("failed",A,1)).result());
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
