package com.physicaldestroydeform.collision;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import com.physicaldestroydeform.collision.RealFixBoundaryCandidates.Reason;
import com.physicaldestroydeform.collision.RealFixBoundsEvidence.Box;

final class RealFixBoundaryCandidatesTest {
    @Test void singleWorldFootprintIsBounded() {
        var result=RealFixBoundaryCandidates.nearOverlap(new Box(0.1,10.1,0.1,0.9,10.9,0.9),1024);
        assertEquals(Reason.CANDIDATES,result.reason());
        assertEquals(1,result.blocks().size());
    }
    @Test void noUnboundedSweepWhenBigShip() {
        var result=RealFixBoundaryCandidates.nearOverlap(new Box(0,0,0,40,20,40),1024);
        assertEquals(Reason.TOO_BROAD,result.reason());
        assertTrue(result.blocks().isEmpty());
    }
    @Test void distinctNativeContactRegionsAreUnionedWithoutPromotingTarget() {
        var moving=new Box(-2,-2,-2,5,5,5);
        var points=List.of(new FrameMath.Vec(0.4,1.5,0.4),
                new FrameMath.Vec(2.4,1.5,0.4),new FrameMath.Vec(0.4,1.5,0.4));
        var r=RealFixBoundaryCandidates.nearContactPoints(moving,points,32);
        assertEquals(Reason.CANDIDATES,r.reason());
        assertEquals(2,r.blocks().size());
        assertEquals(new RealFixBoundaryCandidates.Block(0,1,0),r.blocks().get(0));
        assertEquals(new RealFixBoundaryCandidates.Block(2,1,0),r.blocks().get(1));
        assertEquals(Reason.TOO_BROAD,RealFixBoundaryCandidates.nearContactPoints(moving,points,1).reason());
        assertEquals(Reason.NO_SWEPT_BOUNDS,RealFixBoundaryCandidates.nearContactPoints(moving,List.of(),32).reason());
    }
    @Test void sweptWorldBoundsIncludePreviousAndCurrentPoseButRemainProvisional() {
        var before=new Box(0,0,0,1,1,1);
        var after=new Box(5,0,0,6,1,1);
        var swept=RealFixBoundsEvidence.swept(before,after);
        assertEquals(new Box(0,0,0,6,1,1),swept);
        assertEquals(after,RealFixBoundsEvidence.swept(null,after));
        assertNull(RealFixBoundsEvidence.swept(before,null));
        var r=RealFixBoundaryCandidates.nearContactPoints(swept,
                List.of(new FrameMath.Vec(2.5,0.5,0.5)),32);
        assertEquals(Reason.CANDIDATES,r.reason());
        assertEquals(1,r.blocks().size());
    }
    @Test void missingSourceAndBudgetAreRejected() {
        assertEquals(Reason.NO_SWEPT_BOUNDS,RealFixBoundaryCandidates.nearOverlap(null,1024).reason());
        assertEquals(Reason.TOO_BROAD,RealFixBoundaryCandidates.nearOverlap(
                new Box(0,0,0,1,1,1),0).reason());
    }
}
