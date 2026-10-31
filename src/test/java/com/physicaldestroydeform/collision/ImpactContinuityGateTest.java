package com.physicaldestroydeform.collision;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.physicaldestroydeform.collision.ImpactMath.*;

class ImpactContinuityGateTest {
    private final ImpactContinuityGate.Pair pair = new ImpactContinuityGate.Pair("overworld","PLOT:A","WORLD");
    private final Result credit = new Result(Kind.CONSERVATIVE_PROXY,Reason.APPROVED,3,2,1.5,"FACE");
    @Test void repeatedPhysicsStepsAndRestingTicksNeverMintAnotherBudget() {
        var gate = new ImpactContinuityGate();
        assertTrue(gate.observe(pair,10,credit).approved());
        assertEquals(Reason.DUPLICATE_EPISODE,gate.observe(pair,10,credit).reason());
        assertEquals(Reason.DUPLICATE_EPISODE,gate.observe(pair,11,credit).reason());
        assertEquals(Reason.DUPLICATE_EPISODE,gate.observe(pair,12,credit).reason());
        assertTrue(gate.observe(pair,14,credit).approved()); // tick 13 had no contact
    }
    @Test void rejectedFirstObservationCannotConsumeLaterApprovedBudget() {
        var gate = new ImpactContinuityGate();
        assertEquals(Reason.DIRECTION_UNVERIFIED,
                gate.observe(pair,10,Result.reject(Reason.DIRECTION_UNVERIFIED)).reason());
        assertTrue(gate.observe(pair,10,credit).approved());
        assertEquals(Reason.DUPLICATE_EPISODE,gate.observe(pair,11,credit).reason());
    }
    @Test void timeAndOwnerAreStrictlyScoped() {
        var gate = new ImpactContinuityGate();
        assertTrue(gate.observe(pair,10,credit).approved());
        assertTrue(gate.observe(new ImpactContinuityGate.Pair("overworld","PLOT:B","WORLD"),10,credit).approved());
        assertEquals(Reason.STALE_SNAPSHOT,gate.observe(pair,9,credit).reason());
    }
}
