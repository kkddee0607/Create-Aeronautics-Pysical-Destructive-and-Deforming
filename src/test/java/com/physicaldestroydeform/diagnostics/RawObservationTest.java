package com.physicaldestroydeform.diagnostics;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RawObservationTest {
    @Test void rawValuesRemainUncalibratedAndOwnedByNobodyYet() {
        var sample = new RawObservation(123, RawObservation.SOURCE_DIRECT_CALLBACK,
                RawObservation.OWNER_UNKNOWN, RawObservation.FRAME_UNVERIFIED,
                "minecraft:oak_planks", "SableFragile", true, -12, 70, 8,
                false, 0, 0, 0, Double.NaN, 2.5, -0.0, -4.0, true);
        assertTrue(Double.isNaN(sample.impactX()));
        assertEquals(-4.0, sample.signedVelocity());
        assertFalse(sample.hasOther());
        assertTrue(sample.hadOriginal());
        assertTrue(sample.removeCollision());
    }
}
