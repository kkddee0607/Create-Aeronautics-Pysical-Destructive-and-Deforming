package com.physicaldestroydeform.diagnostics;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CollisionObservationTest {
    @Test void retainsRawCoordinatesWithUnknownFrame() {
        var a = new CollisionObservation.BlockCoordinate(1, 2, 3);
        var c = new CollisionObservation(0, 0, "raw", "UNKNOWN", a, null, 1, 2, 3, 4, false);
        assertEquals(1, c.hitBlock().x());
        assertTrue(c.hasUsableVelocityMagnitude());
        assertThrows(IllegalArgumentException.class,
                () -> new CollisionObservation(0, 0, "raw", "UNKNOWN", a, null, 0, 0, 0, 1, true));
    }
}
