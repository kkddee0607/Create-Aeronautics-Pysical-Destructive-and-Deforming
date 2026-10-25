package com.physicaldestroydeform.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CollisionObservationTest {
    private static CollisionObservation observation(boolean hasOther) {
        return new CollisionObservation(10, 2, "test", "sublevel-plot",
                new CollisionObservation.BlockCoordinate(1, 2, 3),
                hasOther ? new CollisionObservation.BlockCoordinate(4, 5, 6) : null,
                1.0, 2.0, 3.0, -4.0, hasOther);
    }
    @Test void allowsNullOtherBlockOnlyWhenFlagIsFalse() {
        assertDoesNotThrow(() -> observation(false));
        assertDoesNotThrow(() -> observation(true));
        assertThrows(IllegalArgumentException.class, () -> new CollisionObservation(0, 0, "test", "global",
                new CollisionObservation.BlockCoordinate(0, 0, 0), null, 0, 0, 0, 0, true));
    }
    @Test void rejectsNonFiniteVelocityAndPosition() {
        assertThrows(IllegalArgumentException.class, () -> new CollisionObservation(0, 0, "test", "global",
                new CollisionObservation.BlockCoordinate(0, 0, 0), null, Double.NaN, 0, 0, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new CollisionObservation(0, 0, "test", "global",
                new CollisionObservation.BlockCoordinate(0, 0, 0), null, 0, 0, 0, Double.POSITIVE_INFINITY, false));
    }
    @Test void velocitySignIsPreserved() { assertEquals(-4.0, observation(false).impactVelocity()); }
}
