package com.physicaldestroydeform.collision;

import org.junit.jupiter.api.Test;
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
    @Test void missingSourceAndBudgetAreRejected() {
        assertEquals(Reason.NO_SWEPT_BOUNDS,RealFixBoundaryCandidates.nearOverlap(null,1024).reason());
        assertEquals(Reason.TOO_BROAD,RealFixBoundaryCandidates.nearOverlap(
                new Box(0,0,0,1,1,1),0).reason());
    }
}
