package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.MaterialProperties;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ColFix2PropagationTest {
    private static final String WORLD="WORLD:minecraft:overworld";
    private static final String PLOT="PLOT:a9667991-83c3-4a18-b6e6-86844635111b";
    private static final GameImpactCredit.Settings F=GameImpactCredit.Settings.provisional();
    private static final DamageModel.Settings D=DamageModel.Settings.defaults();
    private static GameImpactCredit.Target t(String owner,int x,int y,int z) {
        return new GameImpactCredit.Target(owner,x+","+y+","+z,"minecraft:stone|solid");
    }
    private static ForcePropagation.NeighborReader chain(String owner,int count,Set<Integer> elastic) {
        return (from,x,y,z)->y==0 && z==0 && x>=0 && x<count
                ?ForcePropagation.Probe.present(new GameImpactCredit.Target(owner,
                    x+",0,0",elastic.contains(x)?"minecraft:slime_block|elastic":"minecraft:stone|solid"))
                :ForcePropagation.Probe.empty();
    }
    private static ForcePropagation.Result run(String owner,int count,Set<Integer> elastic,
                                               ForcePropagation.Orientation orientation) {
        return ForcePropagation.plan(owner,List.of(t(owner,0,0,0)),100,
                chain(owner,count,elastic),fp->new MaterialProperties(40,100,20,
                        fp.contains("elastic")?100:0,false),F,D,orientation);
    }
    @Test void bothWorldAndPlotRespectFiveBlockTerminal() {
        for(String owner:List.of(WORLD,PLOT)) {
            var r=run(owner,5,Set.of(),new ForcePropagation.Orientation(new FrameMath.Vec(1,0,0),"NATIVE"));
            assertEquals("VERIFIED_TERMINAL",r.endReason());
            assertEquals(5,r.shares().size());
            double[] expected={50,25,12.5,6.25,6.25};
            for(int i=0;i<5;i++)assertEquals(expected[i],r.shares().get(i).allocatedF(),1e-8);
            assertEquals(100,r.assignedF(),1e-8);
        }
    }
    @Test void elasticMiddleDampsPredecessorAndOwnOutgoingButNotAllBranches() {
        var no=run(PLOT,5,Set.of(),new ForcePropagation.Orientation(new FrameMath.Vec(1,0,0),"NATIVE"));
        var yes=run(PLOT,5,Set.of(1),new ForcePropagation.Orientation(new FrameMath.Vec(1,0,0),"NATIVE"));
        assertTrue(yes.shares().get(0).finalQ()<no.shares().get(0).finalQ());
        assertTrue(yes.shares().get(2).allocatedF()<no.shares().get(2).allocatedF());
        assertEquals(100,yes.assignedF()+yes.dampedF()+yes.unresolvedF()
                +yes.audit().thresholdDiscardF(),1e-8);
    }
    @Test void unboundedStoneFloorTerminatesSafelyWithoutFalseEndpointOrBackwardsSpread() {
        ForcePropagation.NeighborReader floor=(from,x,y,z)->
                y<=0 && y>=-40 && Math.abs(x)<=40 && Math.abs(z)<=40
                    ?ForcePropagation.Probe.present(t(WORLD,x,y,z))
                    :ForcePropagation.Probe.empty();
        var r=ForcePropagation.plan(WORLD,List.of(t(WORLD,0,0,0)),1766.73/2,
                floor,fp->new MaterialProperties(24,32,25,22,false),F,D,
                new ForcePropagation.Orientation(new FrameMath.Vec(0,-1,0),"NATIVE"));
        assertTrue(r.shares().size()<=ForcePropagation.MAX_NODES_PER_SIDE);
        assertTrue(r.shares().stream().noneMatch(s->Integer.parseInt(s.target().localBlock().split(",")[1])>0));
        assertTrue(!"VERIFIED_TERMINAL".equals(r.endReason()));
        assertEquals(r.inputF(),r.assignedF()+r.dampedF()+r.unresolvedF()
                +r.audit().thresholdDiscardF(),1e-7);
    }
    @Test void missingNormalUsesBoundedWorkNotArtificialDepthTwoAndDoesNotInventTermination() {
        ForcePropagation.NeighborReader floor=(from,x,y,z)->
                y<=0 && Math.abs(x)<30 && Math.abs(z)<30 && y>=-30
                    ?ForcePropagation.Probe.present(t(WORLD,x,y,z))
                    :ForcePropagation.Probe.empty();
        var r=ForcePropagation.plan(WORLD,List.of(t(WORLD,0,0,0)),10000,
                floor,fp->new MaterialProperties(24,32,25,22,false),F,D,
                ForcePropagation.Orientation.unknown());
        assertTrue(r.shares().size()<=ForcePropagation.MAX_NODES_PER_SIDE);
        assertTrue(r.audit().maxDepth()>2);
        assertEquals(r.inputF(),r.assignedF()+r.dampedF()+r.unresolvedF()
                +r.audit().thresholdDiscardF(),1e-7);
    }
    @Test void unknownChunkCannotReceiveRemainingF() {
        var r=ForcePropagation.plan(WORLD,List.of(t(WORLD,0,0,0)),100,
                (from,x,y,z)->x==1?ForcePropagation.Probe.unknown():ForcePropagation.Probe.empty(),
                fp->new MaterialProperties(0,64,0,0,false),F,D,
                ForcePropagation.Orientation.unknown());
        assertEquals("UNVERIFIED_BOUNDARY",r.endReason());
        assertEquals(50,r.assignedF(),1e-8);
        assertEquals(50,r.unresolvedF(),1e-8);
    }
    @Test void convergentBranchesVisitEachCellOnceAndConserveF() {
        Set<String> cells=Set.of("0,0,0","1,0,0","0,1,0","1,1,0");
        var r=ForcePropagation.plan(WORLD,List.of(t(WORLD,0,0,0)),100,
                (from,x,y,z)->cells.contains(x+","+y+","+z)
                    ?ForcePropagation.Probe.present(t(WORLD,x,y,z)):ForcePropagation.Probe.empty(),
                fp->new MaterialProperties(0,64,20,0,false),F,D,
                ForcePropagation.Orientation.unknown());
        assertEquals(4,r.shares().size());
        assertEquals(4L,r.shares().stream().map(s->s.target().localBlock()).distinct().count());
        assertEquals(100,r.assignedF()+r.dampedF()+r.unresolvedF(),1e-8);
    }
    @Test void hugeFStillRetainsFiniteSourceEnvelopeUnderCaps() {
        var r=ForcePropagation.plan(PLOT,List.of(t(PLOT,0,0,0)),1e70,
                chain(PLOT,200,Set.of()),fp->new MaterialProperties(40,100,20,0,false),F,D,
                new ForcePropagation.Orientation(new FrameMath.Vec(1,0,0),"NATIVE"));
        assertTrue(r.shares().size()<=ForcePropagation.MAX_NODES_PER_SIDE);
        assertTrue(Double.isFinite(r.finalQ()));
        assertEquals(1e70,r.assignedF()+r.dampedF()+r.unresolvedF(),1e55);
    }
    @Test void trueLastBlockAtUnknownDepthFrontierStillTakesAllResidualF() {
        var r=run(WORLD,3,Set.of(),ForcePropagation.Orientation.unknown());
        assertEquals("VERIFIED_TERMINAL",r.endReason());
        assertEquals(3,r.shares().size());
        assertEquals(25,r.shares().getLast().allocatedF(),1e-8);
        assertEquals(0,r.unresolvedF(),1e-8);
    }
    @Test void isolatedElasticBranchCannotDiscountOtherSibling() {
        var cells=new HashSet<>(Set.of("0,0,0","1,0,0","0,1,0"));
        ForcePropagation.NeighborReader provider=(from,x,y,z)->cells.contains(x+","+y+","+z)
                    ?ForcePropagation.Probe.present(new GameImpactCredit.Target(WORLD,
                        x+","+y+","+z,x==1?"minecraft:slime_block|elastic":"minecraft:stone|solid"))
                    :ForcePropagation.Probe.empty();
        var initial=t(WORLD,0,0,0);
        var plain=ForcePropagation.plan(WORLD,List.of(initial),100,provider,
                fp->new MaterialProperties(40,100,20,0,false),F,D,ForcePropagation.Orientation.unknown());
        var elastic=ForcePropagation.plan(WORLD,List.of(initial),100,provider,
                fp->new MaterialProperties(40,100,20,fp.contains("elastic")?100:0,false),F,D,
                ForcePropagation.Orientation.unknown());
        var sibling=plain.shares().stream().filter(s->s.target().localBlock().equals("0,1,0")).findFirst().orElseThrow();
        var same=elastic.shares().stream().filter(s->s.target().localBlock().equals("0,1,0")).findFirst().orElseThrow();
        assertEquals(sibling.finalQ(),same.finalQ(),1e-8);
        assertTrue(elastic.shares().getFirst().finalQ()<plain.shares().getFirst().finalQ());
    }
}
