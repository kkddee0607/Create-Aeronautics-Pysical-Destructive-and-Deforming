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
        assertEquals(Quality.DIAGNOSTIC_ONLY,first.quality());
        assertFalse(first.canMutateWorld());
        Evidence second=frame.observe(1,70,1,true,40,70,40,2,3,4,0);
        assertEquals(b,second.other().stablePlotId());
        assertEquals(12,second.contactWorldHypothesis().x(),1e-9);
        assertEquals(12,second.otherWorldHypothesis().x(),1e-9);
        assertNotNull(second.hitBody());
        assertNotNull(second.otherBody());
        assertNull(frame.observe(100,70,100,false,0,0,0,1,1,1,0).other());
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
    @Test void utFrame02CrossPhysicsEpochAndPostSolverNeverAuthorizesDamage() {
        Snapshot pre=new Snapshot(11,10,"x",Phase.PRE_SOLVER,List.of(),true);
        Snapshot other=new Snapshot(12,10,"x",Phase.PRE_SOLVER,List.of(),true);
        Snapshot post=new Snapshot(11,10,"x",Phase.POST_SOLVER,List.of(),true);
        assertTrue(sameStep(pre,pre));
        assertFalse(sameStep(pre,other));assertFalse(sameStep(pre,post));
        assertFalse(post.observe(1,1,1,false,0,0,0,2,2,2,3).canMutateWorld());
    }
}
