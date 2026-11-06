package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.MaterialProperties;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** colFix3: multi-source normal-guided DAG traversal with frozen owner-local cells. */
class ColFix3DirectionalPropagationTest {
    private static final String WORLD="WORLD:minecraft:overworld";
    private static final String PLOT="PLOT:5069c055-4ebd-415c-b205-a6715b83156d";
    private static final GameImpactCredit.Settings F=GameImpactCredit.Settings.provisional();
    private static final DamageModel.Settings D=DamageModel.Settings.defaults();
    private static GameImpactCredit.Target cell(String owner,int x,int y,int z) {
        return new GameImpactCredit.Target(owner,x+","+y+","+z,"minecraft:stone|Block{minecraft:stone}");
    }
    private static ForcePropagation.NeighborReader graph(String owner,Set<String> occupied) {
        return (from,x,y,z)->occupied.contains(x+","+y+","+z)
                ?ForcePropagation.Probe.present(cell(owner,x,y,z))
                :ForcePropagation.Probe.empty();
    }
    private static ForcePropagation.Result run(String owner,Set<String> occupied,
                                                List<GameImpactCredit.Target> roots,
                                                FrameMath.Vec inward) {
        return ForcePropagation.plan(owner,roots,100,graph(owner,occupied),
                name->new MaterialProperties(40,100,20,0,false),F,D,
                new ForcePropagation.Orientation(inward,"VERIFIED_NATIVE_TEST"));
    }
    private static void conserved(ForcePropagation.Result r) {
        assertEquals(100,r.assignedF()+r.dampedF()+r.unresolvedF(),1e-8);
        assertEquals(r.candidateQ(),r.finalQ()+r.dampedQ(),1e-8);
        assertEquals(r.shares().size(),r.shares().stream().map(s->s.target().localBlock()).distinct().count());
    }
    @Test void worldNormalGuidesForwardAndLaterSidewaysButNeverBackward() {
        var occupied=Set.of("0,0,0","0,1,0","1,1,0","0,-1,0","-1,1,0","1,0,0");
        var r=run(WORLD,occupied,List.of(cell(WORLD,0,0,0)),new FrameMath.Vec(0,1,0));
        conserved(r);
        assertEquals("VERIFIED_TERMINAL",r.endReason());
        assertEquals(4,r.shares().size());
        assertFalse(r.shares().stream().anyMatch(s->s.target().localBlock().equals("0,-1,0")));
        assertFalse(r.shares().stream().anyMatch(s->s.target().localBlock().equals("1,0,0")));
        assertEquals(0,r.shares().getFirst().layer());
        assertTrue(r.shares().stream().anyMatch(s->s.target().localBlock().equals("1,1,0")));
    }
    @Test void obliqueNormalAllowsBothPositiveAxesWithoutRoundingToCardinal() {
        var occupied=Set.of("0,0,0","1,0,0","0,1,0","1,1,0","-1,0,0","0,-1,0");
        var r=run(PLOT,occupied,List.of(cell(PLOT,0,0,0)),new FrameMath.Vec(1,1,0));
        conserved(r);
        assertEquals(4,r.shares().size());
        assertTrue(r.shares().stream().anyMatch(s->s.target().localBlock().equals("1,0,0")));
        assertTrue(r.shares().stream().anyMatch(s->s.target().localBlock().equals("0,1,0")));
        assertFalse(r.shares().stream().anyMatch(s->s.target().localBlock().equals("-1,0,0")));
        assertEquals(2,r.shares().stream().filter(s->s.layer()==1).count());
        assertEquals(1,r.shares().stream().filter(s->s.layer()==2).count());
        assertEquals(25,r.shares().stream().filter(s->s.target().localBlock().equals("1,1,0"))
                .findFirst().orElseThrow().allocatedF(),1e-9);
    }
    @Test void fourContactRootsSplitEqualAndMergePathsWithoutDoubleSpending() {
        var nodes=new HashSet<String>();
        for(int y=0;y<=1;y++)for(int x=0;x<=1;x++)for(int z=0;z<=1;z++)
            nodes.add(x+","+y+","+z);
        var roots=List.of(cell(WORLD,0,0,0),cell(WORLD,1,0,0),
                cell(WORLD,0,0,1),cell(WORLD,1,0,1));
        var r=run(WORLD,nodes,roots,new FrameMath.Vec(0,1,0));
        conserved(r);
        assertEquals(8,r.shares().size());
        assertEquals(4,r.shares().stream().filter(s->s.layer()==0).count());
        assertEquals(4,r.shares().stream().filter(s->s.layer()==1).count());
        for(var s:r.shares())if(s.layer()==0)assertEquals(12.5,s.allocatedF(),1e-9);
        assertTrue(r.shares().stream().allMatch(s->s.layer()<=1));
    }
    @Test void twoRootsDeliverToSharedSecondLayerChildOnlyOnce() {
        var occupied=Set.of("0,0,0","1,0,1","0,1,0","1,1,1","0,1,1");
        var r=run(PLOT,occupied,List.of(cell(PLOT,0,0,0),cell(PLOT,1,0,1)),
                new FrameMath.Vec(0,1,0));
        conserved(r);
        assertEquals(5,r.shares().size());
        var merge=r.shares().stream().filter(s->s.target().localBlock().equals("0,1,1"))
                .findFirst().orElseThrow();
        assertEquals(2,merge.layer());
        assertEquals(25,merge.allocatedF(),1e-9);
        assertEquals("VERIFIED_TERMINAL",r.endReason());
    }
    @Test void unknownBoundaryDoesNotBecomePhysicalTerminalAndRotatingFrameIsNotResampled() {
        var root=cell(PLOT,0,0,0);
        var r=ForcePropagation.plan(PLOT,List.of(root),100,
                (from,x,y,z)->y==1?ForcePropagation.Probe.unknown():ForcePropagation.Probe.empty(),
                name->new MaterialProperties(40,100,20,0,false),F,D,
                new ForcePropagation.Orientation(new FrameMath.Vec(0,1,0),"VERIFIED_NATIVE_TEST"));
        conserved(r);
        assertEquals("UNVERIFIED_BOUNDARY",r.endReason());
        assertEquals(50,r.unresolvedF(),1e-9);
    }
}
