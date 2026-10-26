package com.physicaldestroydeform.diagnostics;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ObservationBufferTest {
    @Test void bufferBoundAndReset() {
        var pos = new CollisionObservation.BlockCoordinate(1, 2, 3);
        var sample = new CollisionObservation(0, 0, "test", "unknown", pos, null, 0, 0, 0, 1, false);
        var buffer = new ObservationBuffer(1);
        assertTrue(buffer.record(sample)); assertFalse(buffer.record(sample));
        assertEquals(1, buffer.droppedCount());
        assertThrows(UnsupportedOperationException.class, () -> buffer.snapshot().clear());
        buffer.drain(); assertEquals(0, buffer.droppedCount()); assertEquals(0, buffer.size());
    }
}
