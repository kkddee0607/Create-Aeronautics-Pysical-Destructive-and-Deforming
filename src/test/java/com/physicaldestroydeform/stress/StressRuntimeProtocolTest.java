package com.physicaldestroydeform.stress;

import com.physicaldestroydeform.*;
import com.physicaldestroydeform.material.CopycatMaterial;
import com.physicaldestroydeform.material.MaterialRegistry;
import com.physicaldestroydeform.state.SparseStateKey;
import com.physicaldestroydeform.world.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.physicaldestroydeform.stress.StressPropagation.*;
import static com.physicaldestroydeform.stress.StressReservationJournal.*;

/** Pure protocol tests; Minecraft adapters still require the user's in-game validation. */
class StressRuntimeProtocolTest {
    @TempDir Path path;
    static final DamageModel.Settings SETTINGS=DamageModel.Settings.defaults();
    static final MaterialProperties MATERIAL=new MaterialProperties(0,1024,0,0,false);
    static SparseStateKey key(int x){return new SparseStateKey("minecraft:overworld","WORLD","minecraft:overworld",1,x,64,0,"minecraft:stone|stone");}
    static Node node(int x){return new Node(StressCommitHandoff.cellFor(key(x)),key(x).fingerprint(),"rev0",MATERIAL);}
    static Reservation reservation(String id,Policy policy){return new Reservation(id,1,100,policy,SETTINGS,List.of(new Seed(node(0),100,null)));}
    static Graph line(int last){return cell->cell.y()==64&&cell.z()==0&&cell.x()>=0&&cell.x()<=last?Probe.solid(node(cell.x())):Probe.empty();}
    static WorldCommitCore.Intent intent(Reservation r,PlannedNode p){return new WorldCommitCore.Intent(applyId(r.creditId(),p.node().cell()),
            StressCommitHandoff.keyFor(p.node().cell(),p.node().fingerprint()),p.node().incarnation(),p.incomingQ(),p.node().material(),r.materialGeneration(),90,false);}
    @Test void terminalReceiptSurvivesOlderWorldClockWithoutReissue()throws Exception {
        var journal=new WorldPersistentJournal(path.resolve("rollback-terminal"),path.resolve("legacy"));
        var receipt=new WorldReceiptJournal.Receipt("terminal",key(0),WorldReceiptJournal.Status.APPLIED,"COMMITTED",500);
        journal.transition(receipt,null);
        var core=new WorldCommitCore(journal,SETTINGS,64);
        var decision=core.reconcileRestart(receipt,null,100);
        assertEquals(WorldCommitCore.Outcome.DUPLICATE,decision.result());
        assertEquals(receipt,journal.receipt(receipt.id()));assertEquals(0,core.queued());
    }
    @Test void capSealsDamageEarlyAndRetainsResidualUnderOriginalDeadline()throws Exception {
        var r=reservation("cap",new Policy(1,128,512,1.0/256,1200));
        var session=new Session(r.seeds(),r.policy(),r.settings(),r.createdGameTick(),true);
        session.advance(line(3),100,512);
        assertTrue(session.ledger().finished());assertEquals(1,session.plans().size());
        assertEquals(12.5,session.ledger().pendingQ(),1e-12);
        assertEquals(100,session.ledger().accountedQ(),1e-12);
        var journal=new StressReservationJournal(path.resolve("stress"));journal.reserve(r);journal.sealProgress(r.creditId(),session,Map.of());
        assertThrows(IllegalStateException.class,()->journal.acknowledgeHandoff(r.creditId()));
        var commitJournal=new WorldPersistentJournal(path.resolve("world"),path.resolve("legacy"));
        var core=new WorldCommitCore(commitJournal,SETTINGS,64);var plan=session.plans().getFirst();
        assertTrue(StressCommitHandoff.offer(core,journal.get(r.creditId()),plan,intent(r,plan),101));
        assertEquals(1300,commitJournal.receipt(intent(r,plan).applyId()).pending().expiresGameTick());
        journal.closeResidual(r.creditId(),"ORIGINAL_TTL_UNUSED");journal.acknowledgeHandoff(r.creditId());
        var saved=new StressReservationJournal(path.resolve("stress")).get(r.creditId());
        assertEquals(Phase.CLOSED,saved.phase());assertEquals(25,saved.ledger().unusedQ(),1e-12);
        assertEquals(60,saved.ledger().directQ(),1e-12);assertEquals(15,saved.ledger().plasticUnusedQ(),1e-12);
    }
    @Test void unloadedFrontierRetriesWhilePriorOutputIsAlreadyDurable()throws Exception {
        var r=reservation("unloaded",Policy.defaults());var journal=new StressReservationJournal(path.resolve("stress"));journal.reserve(r);
        var session=new Session(r.seeds(),r.policy(),r.settings(),100,true);
        Graph unavailable=cell->cell.equals(node(3).cell())?Probe.unavailable():line(2).read(cell);
        session.advance(unavailable,100,512);
        assertFalse(session.ledger().finished());assertEquals(1,session.partialPlans().size());
        assertEquals(12.5,session.ledger().pendingQ(),1e-12);
        journal.sealProgress(r.creditId(),session,Map.of(node(3).cell(),Probe.unavailable()));
        var first=journal.get(r.creditId()).plans().getFirst();
        var commitJournal=new WorldPersistentJournal(path.resolve("world"),path.resolve("legacy"));
        var core=new WorldCommitCore(commitJournal,SETTINGS,64);
        assertTrue(StressCommitHandoff.offer(core,journal.get(r.creditId()),first,intent(r,first),100));
        session.advance(line(2),101,512);journal.sealProgress(r.creditId(),session,Map.of(node(3).cell(),Probe.empty()));
        assertEquals(2,journal.get(r.creditId()).plans().size());
        assertEquals(first,journal.get(r.creditId()).plans().getFirst());
        assertEquals(67.5,session.ledger().directQ(),1e-12);
        assertEquals(16.875,session.ledger().plasticUnusedQ(),1e-12);
        assertEquals(15.625,session.ledger().unusedQ(),1e-12);
        assertTrue(StressCommitHandoff.offer(core,journal.get(r.creditId()),first,intent(r,first),101));
        assertEquals(1,core.queued()); // stable published ID never receives Q twice
    }
    @Test void fullOriginAndDisjointPartitionsSurviveCrashBeforeDirectEnqueue()throws Exception {
        var origin=new WorldCommitCore.Intent("direct-original",key(0),"rev0",400,MATERIAL,1,90,true);
        var r=new Reservation("origin",1,100,Policy.defaults(),SETTINGS,List.of(new Seed(node(0),100,null)),List.of(origin));
        var journal=new StressReservationJournal(path.resolve("stress"));journal.reserve(r);
        journal.snapshot(r.creditId(),Map.of(node(0).cell(),Probe.solid(node(0)),node(1).cell(),Probe.solid(node(1))));
        var restored=new StressReservationJournal(path.resolve("stress")).get(r.creditId());
        assertEquals(origin,restored.reservation().origins().getFirst());assertEquals(2,restored.snapshot().size());
        var partition=DamageModel.plan(restored.reservation().origins().getFirst().sealedQ(),MATERIAL,new BlockDamageState(),restored.reservation().settings());
        assertEquals(240,partition.directQ());assertEquals(60,partition.plasticQ());assertEquals(100,restored.reservation().reservedQ());
        assertEquals(400,partition.absorbedQ()+partition.directQ()+partition.plasticQ()+restored.reservation().reservedQ());
        assertThrows(IllegalArgumentException.class,()->new Reservation("wrong",1,100,Policy.defaults(),SETTINGS,
                List.of(new Seed(node(0),160,null)),List.of(origin))); // plastic cannot fund stress
    }
    @Test void materialRevisionAndDirectionSurviveReservationAndPendingRoundTrip()throws Exception {
        var axes=new com.physicaldestroydeform.deform.MovePlan.SignedAxes(1,0,-1,true,77);
        var evidence=new com.physicaldestroydeform.deform.MovePlan.Evidence(axes,900,2,34.5);
        var origin=new WorldCommitCore.Intent("cc-origin",key(0),"rev0",400,MATERIAL,17,90,true,"material-A",evidence);
        var root=new Node(node(0).cell(),key(0).fingerprint(),"rev0",MATERIAL,"material-A");
        var direction=new Direction(axes.x(),axes.y(),axes.z());
        var r=new Reservation("cc-reserve",17,100,Policy.defaults(),SETTINGS,List.of(new Seed(root,100,direction)),List.of(origin));
        var reservations=new StressReservationJournal(path.resolve("stress"));reservations.reserve(r);
        assertEquals(r,new StressReservationJournal(path.resolve("stress")).get(r.creditId()).reservation());
        var damage=new WorldPersistentJournal(path.resolve("world"),path.resolve("legacy"));
        var core=new WorldCommitCore(damage,SETTINGS,64);
        core.submitSealed(origin,SETTINGS,100,1300);
        var pending=new WorldPersistentJournal(path.resolve("world"),path.resolve("legacy")).receipt(origin.applyId()).pending();
        assertEquals(origin,pending.intent());assertEquals(1300,pending.expiresGameTick());
    }
    @Test void graphSnapshotReplayKeepsOriginalIdentityAndOutputIds()throws Exception {
        var r=reservation("snapshot",Policy.defaults());var journal=new StressReservationJournal(path.resolve("stress"));journal.reserve(r);
        Map<Cell,Probe> snapshot=new TreeMap<>();Graph capture=cell->{var p=line(2).read(cell);snapshot.put(cell,p);return p;};
        var session=new Session(r.seeds(),r.policy(),r.settings(),100,true);session.advance(capture,100,512);
        journal.sealProgress(r.creditId(),session,snapshot);
        var restored=new StressReservationJournal(path.resolve("stress")).get(r.creditId());
        var replay=new Session(restored.reservation().seeds(),restored.reservation().policy(),restored.reservation().settings(),100,true);
        replay.advance(cell->{var probe=restored.snapshot().get(cell);assertNotNull(probe);return probe;},101,512);
        assertEquals(session.plans(),replay.plans());assertEquals(session.ledger(),replay.ledger());
        assertEquals(applyId(r.creditId(),session.plans().getFirst().node().cell()),applyId(r.creditId(),replay.plans().getFirst().node().cell()));
    }
    @Test void byteEquivalentSnapshotAndSealDoNotReplaceDurableEntry()throws Exception {
        var r=reservation("idempotent-checkpoint",Policy.defaults());
        var directory=path.resolve("stress-idempotent");
        var journal=new StressReservationJournal(directory);journal.reserve(r);
        var graph=new TreeMap<Cell,Probe>();
        graph.put(node(0).cell(),Probe.solid(node(0)));
        graph.put(node(1).cell(),Probe.unavailable());
        journal.snapshot(r.creditId(),graph);
        var frozen=journal.get(r.creditId());
        journal.snapshot(r.creditId(),new TreeMap<>(graph));
        assertSame(frozen,journal.get(r.creditId()));
        var session=new Session(r.seeds(),r.policy(),r.settings(),100,true);
        Graph blocked=cell->cell.equals(node(1).cell())?Probe.unavailable():line(1).read(cell);
        session.advance(blocked,100,1);journal.sealProgress(r.creditId(),session,graph);
        var first=journal.get(r.creditId());
        assertTrue(first.holdsOrigins());assertEquals(100,first.ledger().pendingQ(),1e-12);
        session.advance(blocked,101,1);journal.sealProgress(r.creditId(),session,graph);
        assertSame(first,journal.get(r.creditId()));
        // A changed snapshot with identical plans and Q still MUST persist.
        graph.put(node(1).cell(),Probe.solid(node(1)));
        journal.sealProgress(r.creditId(),session,graph);
        var changed=journal.get(r.creditId());
        assertNotSame(first,changed);
        assertEquals(first.ledger(),changed.ledger());
        assertEquals(changed,new StressReservationJournal(directory).get(r.creditId()));
        // A changed ledger/plan with an identical snapshot also MUST persist.
        session.advance(line(1),102,512);journal.sealProgress(r.creditId(),session,graph);
        var complete=journal.get(r.creditId());
        assertNotSame(changed,complete);
        assertFalse(complete.holdsOrigins());assertEquals(1,complete.plans().size());
        assertEquals(60,complete.ledger().directQ(),1e-12);
        assertEquals(15,complete.ledger().plasticUnusedQ(),1e-12);
        assertEquals(25,complete.ledger().unusedQ(),1e-12);
        assertEquals(0,complete.ledger().pendingQ(),1e-12);
        assertEquals(complete,new StressReservationJournal(directory).get(r.creditId()));
    }
    @Test void publishedCellIndexTracksDurableNotSpeculativeOutputs()throws Exception {
        var r=reservation("published-index",Policy.defaults());
        var journal=new StressReservationJournal(path.resolve("stress-published-index"));
        journal.reserve(r);
        var index=new StressRuntime.PublishedCells();
        assertFalse(index.contains(journal.get(r.creditId()),node(0).cell()));
        var session=new Session(r.seeds(),r.policy(),r.settings(),100,true);
        session.advance(cell->cell.equals(node(1).cell())?Probe.unavailable():line(1).read(cell),100,1);
        journal.sealProgress(r.creditId(),session,Map.of());
        assertFalse(index.contains(journal.get(r.creditId()),node(0).cell()));
        session.advance(line(1),101,1); // root complete; neighbor only a future output
        journal.sealProgress(r.creditId(),session,Map.of());
        assertTrue(index.contains(journal.get(r.creditId()),node(0).cell()));
        assertFalse(index.contains(journal.get(r.creditId()),node(1).cell()));
        session.advance(line(1),102,512);journal.sealProgress(r.creditId(),session,Map.of());
        var complete=journal.get(r.creditId());
        assertTrue(index.contains(complete,node(0).cell()));
        assertTrue(index.contains(complete,node(1).cell()));
        assertFalse(index.contains(complete,node(2).cell()));
    }
    @Test void immutableMaterialLookupReusesOnlyOrdinarySameTickSameSnapshotValues(){
        var snapshot=new MaterialRegistry.Snapshot(1,Map.of(),Map.of(),"rev");
        var next=new MaterialRegistry.Snapshot(1,Map.of(),Map.of(),"rev");
        var lookup=new StressRuntime.StaticMaterialLookup();
        var ordinary=key(0).fingerprint();
        var first=new CopycatMaterial.Resolved(MATERIAL,"",false);
        var changed=new CopycatMaterial.Resolved(new MaterialProperties(1,1024,0,0,false),"",false);
        int[] reads={0};
        assertEquals(first,lookup.resolve(snapshot,ordinary,()->{reads[0]++;return first;}));
        assertEquals(first,lookup.resolve(snapshot,ordinary,()->{reads[0]++;return first;}));
        assertEquals(1,reads[0]);
        assertEquals(changed,lookup.resolve(next,ordinary,()->{reads[0]++;return changed;}));
        assertEquals(2,reads[0]); // new snapshot instance, even same generation
        lookup.beginTick();
        assertEquals(first,lookup.resolve(next,ordinary,()->{reads[0]++;return first;}));
        assertEquals(3,reads[0]); // never retain cache across ticks
        assertNull(lookup.resolve(next,"unknown:block|block",()->{reads[0]++;return null;}));
        assertNull(lookup.resolve(next,"unknown:block|block",()->{reads[0]++;return null;}));
        assertEquals(4,reads[0]);
        String copycat="copycats:copycat_block|block";
        assertEquals(first,lookup.resolve(next,copycat,()->{reads[0]++;return first;}));
        assertEquals(changed,lookup.resolve(next,copycat,()->{reads[0]++;return changed;}));
        assertEquals(6,reads[0]); // even within one tick a material edit must be seen
    }
    @Test void partialOutputPrefixCannotBeReplacedByANewTraversal()throws Exception {
        var r=reservation("immutable-prefix",Policy.defaults());var journal=new StressReservationJournal(path.resolve("stress"));journal.reserve(r);
        var first=new Session(r.seeds(),r.policy(),r.settings(),100,true);first.advance(line(2),100,512);journal.sealProgress(r.creditId(),first,Map.of());
        var changed=new Session(r.seeds(),r.policy(),r.settings(),100,true);changed.advance(line(0),101,512);
        assertThrows(IllegalStateException.class,()->journal.sealProgress(r.creditId(),changed,Map.of()));
        journal.closeResidual(r.creditId(),"SNAPSHOT_REPLACED_UNUSED");
        assertEquals(first.plans(),journal.get(r.creditId()).plans());
    }
    @Test void canonicalOwnerDecoderSeparatesVirtualPlotAndWorldCoordinates(){
        assertEquals(key(0),StressCommitHandoff.keyFor(StressCommitHandoff.cellFor(key(0)),key(0).fingerprint()));
        var real=new SparseStateKey("minecraft:overworld","PLOT","00000000-0000-0000-0000-000000000001",1,0,64,0,"minecraft:stone|stone");
        var virtual=com.physicaldestroydeform.plot.PlotKeyCodec.storage(real);
        assertNotEquals(StressCommitHandoff.cellFor(virtual),StressCommitHandoff.cellFor(key(0)));
        assertEquals(virtual,StressCommitHandoff.keyFor(StressCommitHandoff.cellFor(virtual),virtual.fingerprint()));
        assertThrows(IllegalArgumentException.class,()->StressCommitHandoff.keyFor(new Cell("WORLD:guessed",0,64,0),"stone"));
    }
    @Test void changedGraphDefersOnlyResidualUntilOriginalDeadline()throws Exception {
        var r=reservation("changed",Policy.defaults());var journal=new StressReservationJournal(path.resolve("stress"));journal.reserve(r);
        var session=new Session(r.seeds(),r.policy(),r.settings(),100,true);
        session.advance(cell->cell.equals(node(3).cell())?Probe.unavailable():line(2).read(cell),100,512);
        journal.sealProgress(r.creditId(),session,Map.of());
        journal.deferResidual(r.creditId(),"SNAPSHOT_REPLACED_DEFERRED_BOUNDED");
        var deferred=new StressReservationJournal(path.resolve("stress")).get(r.creditId());
        assertTrue(deferred.ledger().finished());assertEquals(12.5,deferred.ledger().pendingQ(),1e-12);
        assertEquals(session.partialPlans(),deferred.plans());
        assertFalse(StressResidualWait.readyForResidualWait(deferred,false,deferred.plans().size()));
        assertFalse(StressResidualWait.readyForResidualWait(deferred,true,-1));
        assertTrue(StressResidualWait.readyForResidualWait(deferred,true,deferred.plans().size()));
        assertThrows(IllegalStateException.class,()->journal.acknowledgeHandoff(r.creditId()));
        assertEquals(100,deferred.reservation().createdGameTick());
        journal.closeResidual(r.creditId(),"ORIGINAL_TTL_UNUSED");
        assertEquals(0,journal.get(r.creditId()).ledger().pendingQ());
        assertEquals(100,journal.get(r.creditId()).ledger().accountedQ(),1e-12);
    }
    @Test void changedProjectionCannotReserveSameSourceOriginTwiceEvenAfterRestart()throws Exception {
        var origin=new WorldCommitCore.Intent("origin-once",key(0),"rev0",400,MATERIAL,1,90,true);
        var first=new Reservation("first",1,100,Policy.defaults(),SETTINGS,List.of(new Seed(node(0),100,null)),List.of(origin));
        var second=new Reservation("different-projection",1,101,Policy.defaults(),SETTINGS,List.of(new Seed(node(0),100,null)),List.of(origin));
        var journal=new StressReservationJournal(path.resolve("stress"));journal.reserve(first);
        assertThrows(IllegalArgumentException.class,()->journal.reserve(second));
        journal.cancelUnplanned(first.creditId(),"TTL_UNUSED");
        var reopened=new StressReservationJournal(path.resolve("stress"));
        assertThrows(IllegalArgumentException.class,()->reopened.reserve(second));
        assertFalse(reopened.reserve(first));
    }

    @Test void materialGenerationChangeAfterRestartClosesOnlyUnpublishedStressQ()throws Exception {
        var origin=new WorldCommitCore.Intent("generation-origin",key(0),"rev0",400,MATERIAL,1,90,true);
        var r=new Reservation("generation-credit",1,100,Policy.defaults(),SETTINGS,
                List.of(new Seed(node(0),100,null)),List.of(origin));
        var directory=path.resolve("stress-generation");
        var journal=new StressReservationJournal(directory);journal.reserve(r);
        var session=new Session(r.seeds(),r.policy(),r.settings(),100,true);
        session.advance(cell->cell.equals(node(3).cell())?Probe.unavailable():line(2).read(cell),100,512);
        journal.sealProgress(r.creditId(),session,Map.of());
        var reopened=new StressReservationJournal(directory);
        var before=reopened.get(r.creditId());
        assertEquals(1L,before.reservation().materialGeneration());
        assertEquals(12.5,before.ledger().pendingQ(),1e-12);
        var sealed=before.plans();
        // The live runtime invokes this transition on a content-generation mismatch.
        reopened.closeResidual(r.creditId(),"MATERIAL_GENERATION_CHANGED_UNUSED");
        var closed=new StressReservationJournal(directory).get(r.creditId());
        assertEquals(sealed,closed.plans());
        assertEquals(List.of(origin),closed.reservation().origins());
        assertEquals(0,closed.ledger().pendingQ(),1e-12);
        assertEquals(25,closed.ledger().unusedQ(),1e-12);
        assertEquals(60,closed.ledger().directQ(),1e-12);
        assertEquals(15,closed.ledger().plasticUnusedQ(),1e-12);
        assertEquals(100,closed.ledger().accountedQ(),1e-12);
        assertTrue(closed.ledger().finished());
        var replacement=new WorldCommitCore.Intent(origin.applyId(),key(0),"rev0",400,MATERIAL,2,90,true);
        var attempted=new Reservation("generation-credit-new",2,101,Policy.defaults(),SETTINGS,
                List.of(new Seed(node(0),100,null)),List.of(replacement));
        assertThrows(IllegalArgumentException.class,()->reopened.reserve(attempted));
    }

    @Test void frozenRootSurvivesDirectBreakAndStressReachesExistingCommitPipeline()throws Exception {
        var origin=new WorldCommitCore.Intent("direct-break",key(0),"rev0",1000,MATERIAL,1,90,true);
        var r=new Reservation("live-path",1,100,Policy.defaults(),SETTINGS,List.of(new Seed(node(0),250,null)),List.of(origin));
        var reservations=new StressReservationJournal(path.resolve("stress"));reservations.reserve(r);
        var commitJournal=new WorldPersistentJournal(path.resolve("world"),path.resolve("legacy"));
        var core=new WorldCommitCore(commitJournal,SETTINGS,64);
        core.setExecutionHold(i->i.applyId().equals(origin.applyId())&&reservations.get(r.creditId()).holdsOrigins());
        Set<Integer> air=new HashSet<>();int[] breaks={0};
        var gateway=new WorldCommitCore.WorldGateway(){
            public boolean isServerThread(){return true;}
            public WorldCommitCore.Observation readIfLoaded(SparseStateKey k){return new WorldCommitCore.Observation(
                    k.dimension(),air.contains(k.x())?"minecraft:air":k.fingerprint(),1,"rev0",true,air.contains(k.x()),false,false,false,false);}
            public WorldCommitCore.Permission canDamage(SparseStateKey k,WorldCommitCore.Observation o){return WorldCommitCore.Permission.ALLOW;}
            public WorldCommitCore.Permission canBreak(SparseStateKey k,WorldCommitCore.Observation o){return WorldCommitCore.Permission.ALLOW;}
            public boolean breakVanillaOnce(SparseStateKey k){breaks[0]++;air.add(k.x());return true;}
        };
        core.submitSealed(origin,SETTINGS,100,1300);
        assertEquals("RESERVATION_PLANNING_HOLD",core.tickAtGameTime(gateway,100,100).getFirst().reason());
        assertEquals(0,breaks[0]);
        Map<Cell,Probe> snapshot=new TreeMap<>();
        Graph frozen=cell->snapshot.computeIfAbsent(cell,c->air.contains(c.x())?Probe.empty():line(2).read(c));
        var session=new Session(r.seeds(),r.policy(),r.settings(),100,true);
        session.advance(frozen,100,1); // root and its six edges freeze before direct mutation
        reservations.sealProgress(r.creditId(),session,snapshot);
        assertFalse(reservations.get(r.creditId()).holdsOrigins());
        assertEquals(WorldCommitCore.Outcome.APPLIED_BREAK,core.tickAtGameTime(gateway,101,101).getFirst().result());
        assertEquals(1,breaks[0]);assertTrue(air.contains(0));
        session.advance(frozen,102,512);reservations.sealProgress(r.creditId(),session,snapshot);
        assertEquals(2,session.plans().size());
        for(var plan:session.plans())assertTrue(StressCommitHandoff.offer(core,reservations.get(r.creditId()),plan,intent(r,plan),102));
        var results=core.tickAtGameTime(gateway,102,102);
        assertEquals(2,results.size());assertTrue(results.stream().allMatch(d->d.result()==WorldCommitCore.Outcome.APPLIED_DAMAGE));
        assertEquals(390,commitJournal.get(key(1)).damage());
        assertEquals(48,commitJournal.get(key(2)).damage());assertEquals(192,commitJournal.get(key(2)).remainder());
        for(var plan:session.plans())assertTrue(StressCommitHandoff.offer(core,reservations.get(r.creditId()),plan,intent(r,plan),103));
        assertEquals(0,core.queued());assertEquals(1,breaks[0]);
    }

}
