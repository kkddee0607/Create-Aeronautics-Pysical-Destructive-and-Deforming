package com.physicaldestroydeform.collision;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.physicaldestroydeform.collision.TargetGate.*;

class TargetGateTest {
    @Test void independentlyVerifiedTargetsNeverDependOnGeometryOrPairMatching() {
        // Both target audits may pass despite invalid, missing or inconsistent
        // manifold points. Geometry is deliberately not a gate input.
        assertEquals(Outcome.BOTH_VERIFIED, evaluate(true, true, true));
    }
    @Test void noOtherIsNotAnInventedSecondTarget() {
        assertEquals(Outcome.NO_OTHER, evaluate(false, true, false));
        assertEquals(Outcome.NO_OTHER, evaluate(false, true, true));
    }
    @Test void incompleteTargetsRemainUnresolvedRatherThanFallingBack() {
        assertEquals(Outcome.HIT_ONLY, evaluate(true, true, false));
        assertEquals(Outcome.OTHER_ONLY, evaluate(true, false, true));
        assertEquals(Outcome.NEITHER_VERIFIED, evaluate(true, false, false));
    }
}
