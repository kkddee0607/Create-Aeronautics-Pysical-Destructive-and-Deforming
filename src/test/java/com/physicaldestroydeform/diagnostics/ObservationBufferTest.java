package com.physicaldestroydeform.diagnostics;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ObservationBufferTest {
    @Test void bounded() {
        var a = new CollisionObservation.BlockCoordinate(0, 0, 0);
        var item = new CollisionObservation(0, 0, "raw", "UNKNOWN", a, null, 0, 0, 0, 0, false);
        var b = new ObservationBuffer(1);
        assertTrue(b.record(item)); assertFalse(b.record(item));
        assertEquals(1, b.droppedCount());
        assertEquals(1, b.snapshot().size());
        b.drain(); assertEquals(0, b.size());
    }
}
