package com.physicaldestroydeform.collision;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static com.physicaldestroydeform.collision.FrameMath.*;
import static com.physicaldestroydeform.collision.CollisionFrame.*;

class CollisionFrameTest {
    private static final Pose ID=new Pose(new Vec(10,10,10),new Vec(0,0,0),
            new Vec(1,1,1),0,0,0,1);
    private static Body body(UUID id,int min,int max) {
        return new Body(id,min,max,min,max,ID,new Vec(0,1,0),new Vec(0,0,2),
                new Vec(0,0,0),true);
    }
    @Test void utFrame01StaticPlotAndPlotPlotHaveIndependentOwners() {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        Snapshot frame=new Snapshot(12,55,"minecraft:overworld",Phase.PRE_SOLVER,
                List.of(body(a,0,15),body(b,32,47)),true);
        Evidence first=frame.observe(1,70,1,true,100,70,100,2,3,4,-10);
        assertEquals(Space.SABLE_PLOT,first.hit().space());
        assertEquals(a,first.hit().stablePlotId());
        assertEquals(Space.STATIC_WORLD,first.other().space());
        assertEquals(12,first.contactWorldHypothesis().x(),1e-9);
        // Wrong-frame geometry cannot erase the independently identified owner.
        assertEquals(Quality.DIAGNOSTIC_ONLY,first.quality());
        assertEquals("FRAME_UNRESOLVED_CONTACT_OUTSIDE_OWNER",first.reason());
        assertFalse(first.canMutateWorld());
        Evidence second=frame.observe(1,70,1,true,40,70,40,2,3,4,0);
        assertEquals(b,second.other().stablePlotId());
        assertEquals(12,second.contactWorldHypothesis().x(),1e-9);
        assertEquals(2,second.otherContactLocalHypothesis().x(),1e-9);
        assertNotNull(second.hitBody());
        assertNotNull(second.otherBody());
        assertNull(frame.observe(100,70,100,false,0,0,0,1,1,1,0).other());
    }
    @Test void utFrame03ReciprocalCallbacksUseTheirOwnRawCoordinateSpaces() {
        // Same contact sampled in opposite callback frames; plot is far from world origin.
        UUID id=UUID.randomUUID();
        int plotX=20481033, plotZ=20481032;
        Pose pose=new Pose(new Vec(79.0,63.0,-51.0),
                new Vec(plotX,128,plotZ),new Vec(1,1,1),0,0,0,1);
        Body plot=new Body(id,20480000,20482047,20480000,20482047,
                pose,new Vec(0,0,0),new Vec(0,0,0),new Vec(plotX,128,plotZ),true);
        Snapshot step=new Snapshot(79,22716,"minecraft:overworld",Phase.PRE_SOLVER,List.of(plot),true);
        Evidence worldSide=step.observe(78,62,-52,true,plotX,128,plotZ,78.975,63,-51.1,0);
        Evidence plotSide=step.observe(plotX,128,plotZ,true,78,62,-52,
                plotX-0.025,128,plotZ-0.1,0);
        assertEquals(78.975,worldSide.contactWorldHypothesis().x(),1e-6);
        assertEquals(78.975,plotSide.contactWorldHypothesis().x(),1e-6);
        assertEquals(plotX-0.025,worldSide.otherContactLocalHypothesis().x(),1e-6);
        assertEquals(78.975,plotSide.otherContactLocalHypothesis().x(),1e-6);
        assertEquals("HIT_FRAME_HYPOTHESIS_PLAUSIBLE",worldSide.reason());
        assertEquals("HIT_FRAME_HYPOTHESIS_PLAUSIBLE",plotSide.reason());
        assertTrue(reciprocal(worldSide,plotSide));
        assertEquals(0,contactSeparation(worldSide,plotSide),1e-6);
        assertEquals(Quality.DIAGNOSTIC_ONLY,worldSide.quality());
        assertFalse(worldSide.canMutateWorld());
        assertFalse(plotSide.canMutateWorld());
    }
    @Test void utFrame03DifferentEpochOrOwnerCannotBePaired() {
        UUID id=UUID.randomUUID();
        int plotX=20481033, plotZ=20483080;
        Pose pose=new Pose(new Vec(79,63,-51),new Vec(plotX,128,plotZ),
                new Vec(1,1,1),0,0,0,1);
        Body plot=new Body(id,20480000,20482047,20482048,20484095,
                pose,new Vec(0,0,0),new Vec(0,0,0),new Vec(plotX,128,plotZ),true);
        Snapshot pre=new Snapshot(9,12,"x",Phase.PRE_SOLVER,List.of(plot),true);
        Evidence a=pre.observe(plotX,128,plotZ,true,78,62,-52,plotX,128,plotZ,0);
        Evidence b=pre.observe(78,62,-52,true,plotX,128,plotZ,79,63,-51,0);
        assertTrue(reciprocal(a,b));
        assertEquals(0,contactSeparation(a,b),1e-9);
        Evidence newer=new Snapshot(10,12,"x",Phase.PRE_SOLVER,List.of(plot),true)
                .observe(78,62,-52,true,plotX,128,plotZ,79,63,-51,0);
        assertFalse(reciprocal(a,newer));
        assertThrows(IllegalArgumentException.class,()->contactSeparation(a,newer));
    }
    @Test void utFrame03TwoPlotsHaveIndependentCoordinatesAndSameWorldContact() {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        Body first=new Body(a,0,15,0,15,
                new Pose(new Vec(50,10,50),new Vec(5,70,5),new Vec(1,1,1),0,0,0,1),
                new Vec(0,0,0),new Vec(0,0,0),new Vec(5,70,5),true);
        Body second=new Body(b,32,47,32,47,
                new Pose(new Vec(50,10,50),new Vec(40,70,40),new Vec(1,1,1),0,0,0,1),
                new Vec(0,0,0),new Vec(0,0,0),new Vec(40,70,40),true);
        Snapshot s=new Snapshot(6,40,"overworld",Phase.PRE_SOLVER,List.of(first,second),true);
        Evidence fromA=s.observe(5,70,5,true,40,70,40,5.2,70.2,5.4,0);
        Evidence fromB=s.observe(40,70,40,true,5,70,5,40.2,70.2,40.4,0);
        assertEquals(a,fromA.hit().stablePlotId());
        assertEquals(b,fromB.hit().stablePlotId());
        assertTrue(reciprocal(fromA,fromB));
        assertEquals(0,contactSeparation(fromA,fromB),1e-9);
        assertEquals(40.2,fromA.otherContactLocalHypothesis().x(),1e-9);
        assertEquals(5.2,fromB.otherContactLocalHypothesis().x(),1e-9);
        assertFalse(fromA.canMutateWorld());
    }
    @Test void utFrame04LiveRotatedPlotCoordinatesMatchWorldHit() {
        UUID id=UUID.fromString("99109219-8730-4e1b-b574-7c823eae6cd8");
        Pose livePose=new Pose(new Vec(79.99453735351562,63.3125,-51.27455520629883),
                new Vec(20481034,128.5,20481032.5),new Vec(1,1,1),
                0.9904118776321411,9.228057251675637E-7,
                0.13814659416675568,1.0514376072023879E-6);
        Body body=new Body(id,20480000,20482047,20480000,20482047,
                livePose,new Vec(0,0,0),new Vec(0,0,0),new Vec(20481034,128.5,20481032.5),true);
        Snapshot s=new Snapshot(79,22716,"minecraft:overworld",Phase.PRE_SOLVER,List.of(body),true);
        Evidence world=s.observe(78,62,-52,true,20481033,128,20481032,
                78.97309875488281,63.0,-51.110294342041016,0);
        Evidence plot=s.observe(20481033,128,20481032,true,78,62,-52,
                2.048103306249678E7,128.81249883770943,2.048103206249875E7,0);
        assertTrue(reciprocal(world,plot));
        assertTrue(contactSeparation(world,plot)<1e-4);
        assertEquals("HIT_FRAME_HYPOTHESIS_PLAUSIBLE",world.reason());
        assertEquals("HIT_FRAME_HYPOTHESIS_PLAUSIBLE",plot.reason());
        assertEquals(Quality.DIAGNOSTIC_ONLY,plot.quality());
    }
    @Test void utFrame02OverlapsInvalidBodyAndBadScalarsReject() {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        Snapshot overlap=new Snapshot(1,1,"x",Phase.PRE_SOLVER,List.of(body(a,0,15),body(b,0,15)),true);
        assertEquals(Quality.INVALID,overlap.observe(1,1,1,false,0,0,0,1,1,1,1).quality());
        Snapshot frame=new Snapshot(1,1,"x",Phase.PRE_SOLVER,List.of(body(a,0,15)),true);
        assertEquals(Quality.INVALID,frame.observe(1,1,1,false,0,0,0,Double.NaN,1,1,1).quality());
        assertEquals(Quality.INVALID,frame.observe(1,1,1,false,0,0,0,1,1,1,Double.NaN).quality());
        Snapshot missing=new Snapshot(1,1,"x",Phase.PRE_SOLVER,List.of(),false);
        assertEquals(Quality.INVALID,missing.observe(100,1,100,false,0,0,0,1,1,1,1).quality());
    }
    @Test void stationaryPlotStillKeepsItsOwnerWhenPhysicsHandleIsUnavailable() {
        UUID id=UUID.randomUUID();
        Body inactive=new Body(id,32,47,32,47,ID,
                null,null,null,false);
        Snapshot step=new Snapshot(1,1,"minecraft:overworld",Phase.PRE_SOLVER,List.of(inactive),true);
        Evidence e=step.observe(40,70,40,true,10,70,10,
                Double.NaN,0,0,0);
        assertEquals(Space.SABLE_PLOT,e.hit().space());
        assertEquals(id,e.hit().stablePlotId());
        assertTrue(e.hit().valid());
        assertEquals(Quality.INVALID,e.quality()); // geometry only
        assertFalse(e.canMutateWorld());
    }

    @Test void utFrame02CrossPhysicsEpochAndPostSolverNeverAuthorizesDamage() {
        Snapshot pre=new Snapshot(11,10,"x",Phase.PRE_SOLVER,List.of(),true);
        Snapshot other=new Snapshot(12,10,"x",Phase.PRE_SOLVER,List.of(),true);
        Snapshot post=new Snapshot(11,10,"x",Phase.POST_SOLVER,List.of(),true);
        assertTrue(sameStep(pre,pre));
        assertFalse(sameStep(pre,other));assertFalse(sameStep(pre,post));
        assertFalse(post.observe(1,1,1,false,0,0,0,2,2,2,3).canMutateWorld());
    }
}
