package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.MaterialProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** spreadFix: a sparse solver corner must not suppress verified lateral flow. */
class SpreadFixDirectionalTest {
    private static final String OWNER="WORLD:minecraft:overworld";
    private static GameImpactCredit.Target target(int x,int y,int z) {
        return new GameImpactCredit.Target(OWNER,x+","+y+","+z,"minecraft:stone");
    }
    private static ForcePropagation.Result plan(Set<String> cells, FrameMath.Vec normal,
                                                 List<ForcePropagation.Trace> trace,double f) {
        return ForcePropagation.plan(OWNER,List.of(target(0,0,0)),f,
                (from,x,y,z)->cells.contains(x+","+y+","+z)
                        ?ForcePropagation.Probe.present(target(x,y,z))
                        :ForcePropagation.Probe.empty(),
                ignored->new MaterialProperties(0,255,0,0,false),
                GameImpactCredit.Settings.provisional(),DamageModel.Settings.defaults(),
                normal==null?ForcePropagation.Orientation.unknown():
                        new ForcePropagation.Orientation(normal,"VERIFIED_NATIVE"),trace::add);
    }
    private static double sent(List<ForcePropagation.Trace> traces,String cell) {
        return traces.stream().filter(e->e instanceof ForcePropagation.Edge)
                .map(e->(ForcePropagation.Edge)e)
                .filter(e->e.parent().localBlock().equals("0,0,0")
                        && e.child().localBlock().equals(cell))
                .mapToDouble(ForcePropagation.Edge::sentF).sum();
    }
    @Test void seventyThirtyOfOutgoingHalfIsThirtyFiveAndSevenPointFiveEach() {
        var trace=new ArrayList<ForcePropagation.Trace>();
        var r=plan(Set.of("0,0,0","0,-1,0","-1,0,0","1,0,0"),
                new FrameMath.Vec(0,-1,0),trace,100);
        assertEquals(35,sent(trace,"0,-1,0"),1e-9);
        assertEquals(7.5,sent(trace,"-1,0,0"),1e-9);
        assertEquals(7.5,sent(trace,"1,0,0"),1e-9);
        assertEquals(100,r.assignedF()+r.dampedF()+r.unresolvedF()+r.audit().thresholdDiscardF(),1e-8);
        assertEquals(r.candidateQ(),r.finalQ()+r.dampedQ()+r.audit().thresholdDiscardQ(),1e-8);
    }
    @Test void cornerOnlySeedCanSpreadOnItsOwnInitialSurface() {
        var trace=new ArrayList<ForcePropagation.Trace>();
        var r=plan(Set.of("0,0,0","1,0,0","2,0,0","3,0,0","4,0,0"),
                new FrameMath.Vec(0,1,0),trace,1_000_000);
        assertTrue(sent(trace,"1,0,0")>0);
        assertEquals(5,r.shares().size());
        assertTrue(r.shares().stream().anyMatch(s->s.target().localBlock().equals("4,0,0")));
    }
    @Test void missingForwardChildRedistributesToVerifiedSides() {
        var trace=new ArrayList<ForcePropagation.Trace>();
        plan(Set.of("0,0,0","-1,0,0","1,0,0"),
                new FrameMath.Vec(0,-1,0),trace,100);
        assertEquals(25,sent(trace,"-1,0,0"),1e-9);
        assertEquals(25,sent(trace,"1,0,0"),1e-9);
    }
    @Test void unknownDirectionUsesOnlyActualNeighborsAndEqualWeights() {
        var trace=new ArrayList<ForcePropagation.Trace>();
        var r=plan(Set.of("0,0,0","0,1,0","-1,0,0","1,0,0"),null,trace,100);
        assertEquals(50.0/3,sent(trace,"0,1,0"),1e-9);
        assertEquals(50.0/3,sent(trace,"-1,0,0"),1e-9);
        assertEquals(50.0/3,sent(trace,"1,0,0"),1e-9);
        assertEquals(4,r.shares().size());
    }
    @Test void backwardDirectionIsNeverPaid() {
        var trace=new ArrayList<ForcePropagation.Trace>();
        var r=plan(Set.of("0,0,0","0,1,0","0,-1,0"),
                new FrameMath.Vec(0,-1,0),trace,100);
        assertEquals(0,sent(trace,"0,1,0"),0);
        assertEquals(50,sent(trace,"0,-1,0"),1e-9);
        assertEquals(2,r.shares().size());
    }
}
