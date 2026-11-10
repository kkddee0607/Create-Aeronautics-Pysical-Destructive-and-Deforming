package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.MaterialProperties;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** DuperFix: finite, owner-local, one-credit Q and F propagation invariants. */
class DuperFixPropagationTest {
    static final String OWNER="PLOT:duper";
    static final MaterialProperties STONE=new MaterialProperties(61,101,10,15,false);
    static final MaterialProperties NO_E=new MaterialProperties(0,100,0,0,false);
    static final MaterialProperties SLIME=new MaterialProperties(0,100,80,100,false);
    static GameImpactCredit.Target at(int x,int y,int z,String material) {
        return new GameImpactCredit.Target(OWNER,x+","+y+","+z,material);
    }
    static GameImpactCredit.Target at(int x,int y,int z) {return at(x,y,z,"stone");}
    static ForcePropagation.Result plan(Set<String> blocks,double force,FrameMath.Vec direction,
                                        List<ForcePropagation.Trace> trace) {
        return ForcePropagation.plan(OWNER,List.of(at(0,0,0)),force,
                (from,x,y,z)->blocks.contains(x+","+y+","+z)?
                        ForcePropagation.Probe.present(at(x,y,z)):ForcePropagation.Probe.empty(),
                name->NO_E,GameImpactCredit.Settings.provisional(),DamageModel.Settings.defaults(),
                direction==null?ForcePropagation.Orientation.unknown():
                        new ForcePropagation.Orientation(direction,"TEST"),trace::add);
    }
    static void balanced(ForcePropagation.Result r) {
        assertEquals(r.inputF(),r.assignedF()+r.dampedF()+r.unresolvedF()+r.audit().thresholdDiscardF(),
                Math.max(1e-7,r.inputF()*1e-9));
        assertEquals(r.candidateQ(),r.finalQ()+r.dampedQ()+r.audit().thresholdDiscardQ(),
                Math.max(1e-6,r.candidateQ()*1e-11));
        assertEquals(r.shares().size(),r.shares().stream().map(s->s.target().localBlock()).distinct().count());
    }
    @Test void exactThreeNodeTerminalTakesRemainingForce() {
        var trace=new ArrayList<ForcePropagation.Trace>();
        var r=plan(Set.of("0,0,0","1,0,0","2,0,0"),100,new FrameMath.Vec(1,0,0),trace);
        balanced(r);
        assertEquals(0,r.unresolvedF(),1e-12);
        assertEquals(List.of(50.0,25.0,25.0),r.shares().stream().map(ForcePropagation.Share::allocatedF).toList());
        assertEquals(12.5,r.audit().terminalRecoveredF(),1e-9);
        assertEquals(1,r.audit().terminalNodes());
        assertEquals("VERIFIED_TERMINAL",r.endReason());
        assertEquals(0,trace.stream().filter(t->t instanceof ForcePropagation.Cutoff).count());
    }
    @Test void deepLineHasNoArbitraryNineOrTwoLayerStop() {
        var blocks=new HashSet<String>();
        for(int x=0;x<18;x++)blocks.add(x+",0,0");
        for(FrameMath.Vec direction:List.of(new FrameMath.Vec(1,0,0))) {
            var r=plan(blocks,2_000_000,direction,new ArrayList<>());
            balanced(r);
            assertEquals(18,r.shares().size());
            assertEquals(17,r.audit().maxDepth());
            assertEquals(0,r.unresolvedF(),1e-9);
            assertTrue(r.audit().terminalRecoveredF()>0);
        }
        var unknown=plan(blocks,2_000_000,null,new ArrayList<>());
        balanced(unknown);
        assertEquals(17,unknown.audit().maxDepth());
        assertEquals(0,unknown.unresolvedF(),1e-8);
    }
    @Test void noFUnderOneEdgeAndNoQUnderOneIssued() {
        var blocks=new HashSet<String>();
        for(int x=0;x<20;x++)blocks.add(x+",0,0");
        var trace=new ArrayList<ForcePropagation.Trace>();
        var r=plan(blocks,100,new FrameMath.Vec(1,0,0),trace);
        balanced(r);
        assertEquals(0,r.unresolvedF(),1e-9);
        assertTrue(r.audit().thresholdDiscardF()>0);
        assertTrue(r.audit().thresholdDiscardQ()>0);
        assertTrue(r.shares().stream().allMatch(s->s.finalQ()==0||s.finalQ()>=1));
        assertTrue(trace.stream().filter(t->t instanceof ForcePropagation.Edge)
                .map(t->(ForcePropagation.Edge)t).allMatch(e->e.sentF()>=1));
    }
    @Test void diamondMergeCannotMintExtraForce() {
        var r=plan(Set.of("0,0,0","1,0,0","0,0,1","1,0,1"),100,
                new FrameMath.Vec(1,0,1),new ArrayList<>());
        balanced(r);
        assertEquals(100,r.assignedF(),1e-8);
        assertEquals(0,r.unresolvedF(),1e-8);
        assertEquals(1,r.audit().mergedNodes());
        assertEquals(25.0,r.shares().stream().filter(s->s.target().localBlock().equals("1,0,1"))
                .findFirst().orElseThrow().allocatedF(),1e-8);
    }
    @Test void mixedSlimeAttenuatesOnlyActualUpstreamPaths() {
        var trace=new ArrayList<ForcePropagation.Trace>();
        var r=ForcePropagation.plan(OWNER,List.of(at(0,0,0)),200,
                (from,x,y,z)->x>0&&x<3&&y==0&&z==0?
                        ForcePropagation.Probe.present(at(x,y,z,x==1?"slime":"stone")):
                        ForcePropagation.Probe.empty(),
                material->material.equals("slime")?SLIME:NO_E,
                GameImpactCredit.Settings.provisional(),DamageModel.Settings.defaults(),
                new ForcePropagation.Orientation(new FrameMath.Vec(1,0,0),"TEST"),trace::add);
        balanced(r);
        assertTrue(r.dampedF()>0);
        assertTrue(r.dampedQ()>0);
        assertEquals(200,r.assignedF()+r.dampedF()+r.audit().thresholdDiscardF(),1e-8);
        assertEquals(GameImpactCredit.Settings.provisional().candidate(100)*.15,
                r.shares().getFirst().finalQ(),1e-7);
    }
    @Test void unknownBoundaryIsNeverInventedTerminal() {
        var r=ForcePropagation.plan(OWNER,List.of(at(0,0,0)),100,
                (from,x,y,z)->x==1?ForcePropagation.Probe.unknown():ForcePropagation.Probe.empty(),
                name->NO_E,GameImpactCredit.Settings.provisional(),DamageModel.Settings.defaults(),
                new ForcePropagation.Orientation(new FrameMath.Vec(1,0,0),"TEST"));
        balanced(r);
        assertEquals(50,r.unresolvedF(),1e-8);
        assertEquals(0,r.audit().terminalRecoveredF(),1e-8);
        assertEquals(1,r.audit().safetyBlockedNodes());
    }
    @Test void radiusThreeAndHalfSphereIsNotTruncatedAt128Nodes() {
        var cells=new HashSet<String>();
        for(int x=-4;x<=4;x++)for(int y=-4;y<=4;y++)for(int z=-4;z<=4;z++)
            if(x*x+y*y+z*z<=12)cells.add(x+","+y+","+z);
        var r=plan(cells,1_000_000,null,new ArrayList<>());
        balanced(r);
        assertTrue(r.shares().size()>128);
        assertEquals(0,r.unresolvedF(),1e-7);
        assertTrue(r.audit().maxDepth()>2);
    }
    @Test void largeConnectedRegionDoesNotFalselyAbsorbAtWorkCap() {
        var cells=new HashSet<String>();
        for(int x=-18;x<=18;x++)for(int y=-18;y<=18;y++)for(int z=-18;z<=18;z++)
            cells.add(x+","+y+","+z);
        var r=plan(cells,1e70,null,new ArrayList<>());
        balanced(r);
        assertTrue(r.shares().size()>=ForcePropagation.MAX_NODES_PER_SIDE-1);
        assertTrue(r.shares().size()<=ForcePropagation.MAX_NODES_PER_SIDE);
        assertEquals("NODE_WORK_BUDGET",r.endReason());
        assertTrue(r.unresolvedF()>0);
        assertTrue(r.audit().safetyBlockedNodes()>0);
    }
    @Test void subUnitSourceForceNeverGeneratesQ() {
        var r=plan(Set.of("0,0,0","1,0,0"),0.9,new FrameMath.Vec(1,0,0),new ArrayList<>());
        balanced(r);
        assertEquals(0,r.finalQ(),0);
        assertEquals(0,r.shares().size());
        assertEquals(0.9,r.audit().thresholdDiscardF(),1e-12);
    }
}
