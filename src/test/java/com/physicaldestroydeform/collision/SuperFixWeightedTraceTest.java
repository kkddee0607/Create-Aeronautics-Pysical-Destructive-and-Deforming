package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.MaterialProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 0.4.0superFix: directional tenths and per-parent F receipts. */
class SuperFixWeightedTraceTest {
    private static final String WORLD="WORLD:minecraft:overworld";
    private static final String PLOT="PLOT:33f85b5b-012a-4c09-a82b-3bad1cad3326";
    private static GameImpactCredit.Target cell(String owner,int x,int y,int z) {
        return new GameImpactCredit.Target(owner,x+","+y+","+z,"minecraft:stone|solid");
    }
    private static ForcePropagation.Result run(String owner,Set<String> cells,
            FrameMath.Vec normal,List<ForcePropagation.Trace> trace) {
        return ForcePropagation.plan(owner,List.of(cell(owner,0,0,0)),100,
                (from,x,y,z)->cells.contains(x+","+y+","+z)
                        ?ForcePropagation.Probe.present(cell(owner,x,y,z))
                        :ForcePropagation.Probe.empty(),
                fp->new MaterialProperties(0,100,0,0,false),
                GameImpactCredit.Settings.provisional(),DamageModel.Settings.defaults(),
                normal==null?ForcePropagation.Orientation.unknown()
                        :new ForcePropagation.Orientation(normal,"NATIVE_TEST"),trace::add);
    }
    private static List<ForcePropagation.Edge> edges(List<ForcePropagation.Trace> all) {
        return all.stream().filter(t->t instanceof ForcePropagation.Edge)
                .map(t->(ForcePropagation.Edge)t).toList();
    }
    private static List<ForcePropagation.Receipt> receipts(List<ForcePropagation.Trace> all) {
        return all.stream().filter(t->t instanceof ForcePropagation.Receipt)
                .map(t->(ForcePropagation.Receipt)t).toList();
    }
    private static void conserved(ForcePropagation.Result r) {
        assertEquals(r.inputF(),r.assignedF()+r.dampedF()+r.unresolvedF(),1e-8);
        assertEquals(r.candidateQ(),r.finalQ()+r.dampedQ(),1e-8);
    }
    @Test void diagonal45SplitsEquallyWithoutRoundingNativeVector() {
        for(String owner:List.of(PLOT,WORLD)) {
            var trace=new ArrayList<ForcePropagation.Trace>();
            var r=run(owner,Set.of("0,0,0","1,0,0","0,0,1"),new FrameMath.Vec(1,0,1),trace);
            conserved(r);
            assertEquals(2,edges(trace).size());
            for(var edge:edges(trace)) {
                assertEquals(0.5,edge.directionWeight(),1e-12);
                assertEquals(25,edge.sentF(),1e-8);
                assertEquals(100,edge.parentReceivedF(),1e-8);
                assertEquals(50,edge.parentLocalF(),1e-8);
                assertEquals(50,edge.parentOutgoingF(),1e-8);
            }
            assertEquals(2,receipts(trace).size());
            assertEquals(100,r.assignedF(),1e-8);
        }
    }
    @Test void obliqueDirectionUsesTenthsAndDoesNotFallBackToEqual() {
        var trace=new ArrayList<ForcePropagation.Trace>();
        var r=run(PLOT,Set.of("0,0,0","1,0,0","0,0,1","-1,0,0"),
                new FrameMath.Vec(0.8,0,0.6),trace);
        conserved(r);
        assertEquals(2,edges(trace).size());
        var x=edges(trace).stream().filter(e->e.child().localBlock().equals("1,0,0")).findFirst().orElseThrow();
        var z=edges(trace).stream().filter(e->e.child().localBlock().equals("0,0,1")).findFirst().orElseThrow();
        assertEquals(0.6,x.directionWeight(),1e-12);
        assertEquals(0.4,z.directionWeight(),1e-12);
        assertEquals(30,x.sentF(),1e-8);
        assertEquals(20,z.sentF(),1e-8);
        assertEquals(50,x.sentF()+z.sentF(),1e-8);
        assertTrue(r.shares().stream().noneMatch(s->s.target().localBlock().equals("-1,0,0")));
    }
    @Test void unknownNormalRetainsUnquantizedEqualThreeWayShares() {
        var trace=new ArrayList<ForcePropagation.Trace>();
        var r=run(WORLD,Set.of("0,0,0","1,0,0","0,1,0","0,0,1"),null,trace);
        conserved(r);
        assertEquals(3,edges(trace).size());
        for(var edge:edges(trace)) {
            assertEquals(1.0/3,edge.directionWeight(),1e-12);
            assertEquals(50.0/3,edge.sentF(),1e-8);
        }
    }
    @Test void twoParentsMergeExactlyOnceAndLedgerBalances() {
        var trace=new ArrayList<ForcePropagation.Trace>();
        var r=run(PLOT,Set.of("0,0,0","1,0,0","0,0,1","1,0,1"),
                new FrameMath.Vec(1,0,1),trace);
        conserved(r);
        var merge=receipts(trace).stream().filter(t->t.child().localBlock().equals("1,0,1"))
                .findFirst().orElseThrow();
        assertEquals(2,merge.parentCount());
        assertEquals(25,merge.totalReceivedF(),1e-8);
        assertTrue(merge.processedOnce());
        assertEquals(25,edges(trace).stream().filter(e->e.child().localBlock().equals("1,0,1"))
                .mapToDouble(ForcePropagation.Edge::sentF).sum(),1e-8);
        assertEquals(1,r.shares().stream().filter(s->s.target().localBlock().equals("1,0,1")).count());
        assertEquals(25,r.shares().stream().filter(s->s.target().localBlock().equals("1,0,1"))
                .findFirst().orElseThrow().allocatedF(),1e-8);
    }
    @Test void tangentFromLaterLayerAndUnknownBoundaryRemainSafe() {
        var trace=new ArrayList<ForcePropagation.Trace>();
        var r=run(WORLD,Set.of("0,0,0","0,1,0","1,1,0","0,-1,0"),
                new FrameMath.Vec(0,1,0),trace);
        conserved(r);
        assertTrue(edges(trace).stream().anyMatch(e->e.child().localBlock().equals("1,1,0")));
        assertFalse(edges(trace).stream().anyMatch(e->e.child().localBlock().equals("0,-1,0")));
        var unknownTrace=new ArrayList<ForcePropagation.Trace>();
        var u=ForcePropagation.plan(WORLD,List.of(cell(WORLD,0,0,0)),100,
                (from,x,y,z)->y==1?ForcePropagation.Probe.unknown():ForcePropagation.Probe.empty(),
                fp->new MaterialProperties(0,100,0,0,false),
                GameImpactCredit.Settings.provisional(),DamageModel.Settings.defaults(),
                new ForcePropagation.Orientation(new FrameMath.Vec(0,1,0),"NATIVE_TEST"),unknownTrace::add);
        assertEquals("UNVERIFIED_BOUNDARY",u.endReason());
        assertEquals(50,u.unresolvedF(),1e-8);
        assertTrue(unknownTrace.isEmpty());
    }
}
