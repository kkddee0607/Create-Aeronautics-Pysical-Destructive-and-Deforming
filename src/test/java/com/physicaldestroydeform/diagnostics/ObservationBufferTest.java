package com.physicaldestroydeform.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ObservationBufferTest {
    private CollisionObservation sample(int x) {
        return new CollisionObservation(1, 0, "test", "global",
                new CollisionObservation.BlockCoordinate(x, 0, 0), null, 0, 0, 0, 1, false);
    }
    @Test void capacityIsBoundedAndDroppedEvidenceIsCounted() {
        ObservationBuffer buffer = new ObservationBuffer(2);
        assertTrue(buffer.record(sample(1))); assertTrue(buffer.record(sample(2)));
        assertFalse(buffer.record(sample(3)));
        assertEquals(2, buffer.size()); assertEquals(1, buffer.droppedCount());
        assertEquals(2, buffer.snapshot().size());
    }
    @Test void snapshotCannotMutateBufferAndDrainResetsCounts() {
        ObservationBuffer buffer = new ObservationBuffer(2); buffer.record(sample(1));
        assertThrows(UnsupportedOperationException.class, () -> buffer.snapshot().clear());
        buffer.drain(); assertEquals(0, buffer.size()); assertEquals(0, buffer.droppedCount());
    }
    @Test void rejectsZeroCapacity() { assertThrows(IllegalArgumentException.class, () -> new ObservationBuffer(0)); }
}
