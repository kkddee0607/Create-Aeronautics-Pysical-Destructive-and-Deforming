package com.physicaldestroydeform.stress;

import java.util.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.physicaldestroydeform.stress.StressRuntimeProtocolTest.*;
import static com.physicaldestroydeform.stress.StressPropagation.*;
import static com.physicaldestroydeform.stress.StressReservationJournal.*;

class AstraFixStressRepairTest {
    @TempDir Path path;
    private static Session session(Reservation r){return new Session(r.seeds(),r.policy(),r.settings(),r.createdGameTick(),true);}
    private static Map<Cell,Probe> graph(int first,int last){var map=new TreeMap<Cell,Probe>();for(int i=first;i<=last;i++)map.put(node(i).cell(),Probe.solid(node(i)));return map;}
    private static Graph frozen(Map<Cell,Probe> map){return c->map.getOrDefault(c,Probe.empty());}
    @Test void retirementPreservesIndependentBranchWeightAndOriginalQ()throws Exception{
        var r=reservation("branch",Policy.defaults());var j=new StressReservationJournal(path.resolve("branch"));j.reserve(r);
        var cells=graph(-1,1);var original=session(r);original.advance(frozen(cells),100,512);
        var repair=StressSnapshotRepair.retire(j.get(r.creditId()),cells,Set.of(node(-1).cell()));assertNotNull(repair);
        assertEquals(Probe.denied(),repair.get(node(-1).cell()));
        var replay=session(r);replay.advance(frozen(repair),101,512);
        assertEquals(1,replay.plans().size());assertEquals(node(1),replay.plans().getFirst().node());
        assertEquals(original.plans().stream().filter(p->p.node().equals(node(1))).findFirst().orElseThrow().incomingQ(),replay.plans().getFirst().incomingQ(),1e-12);
        assertEquals(100,replay.ledger().accountedQ(),1e-12);assertEquals(StressSnapshotRepair.Verdict.SAFE,StressSnapshotRepair.verifyPrefix(j.get(r.creditId()),replay));
    }
    @Test void durablePrefixAndRetiredCellSurviveRestart()throws Exception{
        var r=reservation("prefix",Policy.defaults());var directory=path.resolve("prefix");var j=new StressReservationJournal(directory);j.reserve(r);
        var cells=graph(0,3);var first=session(r);first.advance(frozen(cells),100,2);j.sealProgress(r.creditId(),first,cells);
        var entry=j.get(r.creditId());assertEquals(1,entry.plans().size());
        var repair=StressSnapshotRepair.retire(entry,cells,Set.of(node(2).cell()));assertNotNull(repair);
        var replay=session(r);replay.advance(frozen(repair),101,512);
        assertEquals(StressSnapshotRepair.Verdict.SAFE,StressSnapshotRepair.verifyPrefix(entry,replay));
        assertEquals(entry.plans(),replay.plans());assertEquals(100,replay.ledger().accountedQ(),1e-12);
        j.sealProgress(r.creditId(),replay,repair);var restored=new StressReservationJournal(directory).get(r.creditId());
        assertEquals(j.get(r.creditId()),restored);assertEquals(Probe.denied(),restored.snapshot().get(node(2).cell()));assertEquals(r,restored.reservation());
    }
    @Test void unknownHistoryAndPublishedCellCannotBeRewritten()throws Exception{
        var r=reservation("unknown",Policy.defaults());var j=new StressReservationJournal(path.resolve("unknown"));j.reserve(r);
        var cells=graph(0,3);cells.put(node(3).cell(),Probe.unavailable());
        assertNull(StressSnapshotRepair.retire(j.get(r.creditId()),cells,Set.of(node(3).cell())));
        assertNull(StressSnapshotRepair.retire(j.get(r.creditId()),cells,Set.of(node(9).cell())));
        var first=session(r);first.advance(frozen(cells),100,2);j.sealProgress(r.creditId(),first,cells);
        assertNull(StressSnapshotRepair.retire(j.get(r.creditId()),cells,Set.of(node(1).cell())));
    }
    @Test void batchRetirementKeepsEveryOldWeightAndDoesNotMutateOriginal()throws Exception{
        var r=reservation("batch",Policy.defaults());var j=new StressReservationJournal(path.resolve("batch"));j.reserve(r);
        var cells=graph(-1,1);var changed=new HashSet<Cell>();
        for(int i=10;i<139;i++){var n=node(i);cells.put(n.cell(),Probe.solid(n));changed.add(n.cell());}
        var repaired=StressSnapshotRepair.retire(j.get(r.creditId()),cells,changed);
        assertNotNull(repaired);assertEquals(cells.size(),repaired.size());
        for(var c:changed){assertEquals(State.SOLID,cells.get(c).state());assertEquals(Probe.denied(),repaired.get(c));}
        var replay=session(r);replay.advance(frozen(repaired),101,512);
        assertEquals(100,replay.ledger().accountedQ(),1e-12);
    }
    @Test void alteredPublishedPrefixCannotBeReissued()throws Exception{
        var r=reservation("incompatible",Policy.defaults());var j=new StressReservationJournal(path.resolve("incompatible"));j.reserve(r);
        var cells=graph(0,2);var first=session(r);first.advance(frozen(cells),100,2);j.sealProgress(r.creditId(),first,cells);
        var replay=session(r);replay.advance(frozen(graph(-1,2)),101,512);
        assertEquals(StressSnapshotRepair.Verdict.INCOMPATIBLE,StressSnapshotRepair.verifyPrefix(j.get(r.creditId()),replay));
    }
}
