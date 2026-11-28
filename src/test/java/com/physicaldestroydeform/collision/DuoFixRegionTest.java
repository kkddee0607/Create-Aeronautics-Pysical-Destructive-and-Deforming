package com.physicaldestroydeform.collision;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.physicaldestroydeform.collision.FrameMath.Vec;

class DuoFixRegionTest {
    private static final Vec FLOOR=new Vec(0,1,0),WALL=new Vec(1,0,0);
    private static DirectionalContactRegions.Direction direction(Vec n){return new DirectionalContactRegions.Direction(n,n.times(-1));}
    @Test void floorCannotDiluteIndependentWallNormal(){
        var input=new ArrayList<DirectionalContactRegions.Direction>();
        for(int i=0;i<99;i++)input.add(direction(FLOOR));
        input.add(direction(WALL));
        var groups=DirectionalContactRegions.cluster(input);
        assertEquals(2,groups.size());
        assertTrue(groups.stream().anyMatch(g->g.size()==1&&g.contains(99)));
    }
    @Test void completeLinkDoesNotChainDifferentNormals(){
        var input=List.of(direction(WALL),direction(new Vec(Math.cos(Math.toRadians(25)),Math.sin(Math.toRadians(25)),0)),direction(new Vec(Math.cos(Math.toRadians(50)),Math.sin(Math.toRadians(50)),0)));
        assertEquals(2,DirectionalContactRegions.cluster(input).size());
    }
    @Test void persistentFloorCannotRearmButNewWallIsCandidate(){
        var history=new DirectionalContactRegions();
        var floor=history.observe(direction(FLOOR),1,true,false);
        assertFalse(history.eligible(floor,false,false));
        history.contact(floor,false);
        assertFalse(history.eligible(history.observe(direction(FLOOR),2,true,false),true,false));
        var wall=history.observe(direction(WALL),2,true,false);
        assertTrue(history.eligible(wall,true,false));
        history.contact(wall,true);
        assertFalse(history.eligible(history.observe(direction(WALL),3,true,false),true,false));
    }
    @Test void localGapMustBeConsecutiveAndMissingIsNotGap(){
        var history=new DirectionalContactRegions();var wall=history.observe(direction(WALL),1,true,false);
        history.contact(wall,true);
        history.separated(wall,2);history.unknown(wall,3);history.separated(wall,4);
        assertFalse(history.eligible(wall,true,false));
        history.separated(wall,5);assertTrue(history.eligible(wall,true,false));
        history.contact(wall,true);assertFalse(history.eligible(wall,true,false));
    }
    @Test void incompleteInputPreservesConsumedLatch(){
        var history=new DirectionalContactRegions();var wall=history.observe(direction(WALL),1,true,false);
        history.contact(wall,true);history.unknown(wall,2);
        assertNull(history.observe(direction(WALL),3,false,false));
        assertFalse(history.eligible(history.observe(direction(WALL),4,true,false),true,false));
    }
    @Test void fullPairGapCanSupplyRearm(){
        var history=new DirectionalContactRegions();var wall=history.observe(direction(WALL),1,true,false);history.contact(wall,true);
        assertTrue(history.eligible(history.observe(direction(WALL),9,true,true),false,true));
    }
    @Test void sharedCreditUsesEligiblePeakAndOneTickOnly(){
        var p=GameImpactCredit.Pair.canonical("run","d",new GameImpactCredit.Owner("PLOT:a",1),new GameImpactCredit.Owner("WORLD:d",1));
        var c=new GameImpactCredit(GameImpactCredit.Settings.provisional());
        var a=new GameImpactCredit.Target(p.a().stableKey(),"0,0,0","stone");
        var b=new GameImpactCredit.Target(p.b().stableKey(),"1,0,0","stone");
        var frame=new GameImpactCredit.Frame(p,1,List.of(new GameImpactCredit.ObservedForce(1,-1,50000,0),new GameImpactCredit.ObservedForce(1,-1,30000,1)),false,true,a,b,50000);
        var first=c.stepRegional(frame);
        assertTrue(first.credited());assertEquals(50000,first.peakForce());
        assertEquals(4999.8,first.issuedQ(),1e-9);assertFalse(c.stepRegional(frame).credited());
        var next=new GameImpactCredit.Frame(p,2,frame.records(),false,true,a,b,50000);
        assertTrue(c.stepRegional(next).credited()); // independent region, no pair-wide quiet
    }
}
