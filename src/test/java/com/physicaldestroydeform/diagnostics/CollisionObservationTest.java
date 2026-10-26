package com.physicaldestroydeform.diagnostics;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CollisionObservationTest {
    @Test void retainsSignedVelocityAndNullableCounterpart() {
        var pos = new CollisionObservation.BlockCoordinate(1, 2, 3);
        var observation = new CollisionObservation(10, 2, "test", "unverified", pos, null,
                0, 0, 0, -4.0, false);
        assertEquals(-4, observation.impactVelocity());
        assertFalse(observation.hasOtherBlock());
        assertThrows(IllegalArgumentException.class, () -> new CollisionObservation(10, 2, "test", "unverified",
                pos, null, 0, 0, 0, -4, true));
    }
}
