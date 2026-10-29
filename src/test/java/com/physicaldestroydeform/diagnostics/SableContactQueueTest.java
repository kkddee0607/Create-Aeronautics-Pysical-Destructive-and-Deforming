package com.physicaldestroydeform.diagnostics;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SableContactQueueTest {
    @Test void capacityAndRawPayloadPreserved() {
        SableContactQueue.drain();
        long dropped = SableContactQueue.dropped();
        var contact = new RawObservation(42, RawObservation.SOURCE_NATIVE_CALLBACK,
                RawObservation.OWNER_UNKNOWN, RawObservation.FRAME_UNVERIFIED,
                "minecraft:stone", "NONE", false,
                1, 2, 3, true, 4, 5, 6, 7, 8, 9, -2.0, false);
        for (int i = 0; i < SableContactQueue.CAPACITY + 2; i++) SableContactQueue.offer(contact);
        assertEquals(SableContactQueue.CAPACITY, SableContactQueue.size());
        var records = SableContactQueue.drain();
        assertEquals(SableContactQueue.CAPACITY, records.size());
        assertSame(contact, records.getFirst());
        assertEquals(-2.0, records.getFirst().signedVelocity());
        assertEquals("UNKNOWN", records.getFirst().ownerSpace());
        assertEquals(dropped + 2, SableContactQueue.dropped());
        assertEquals(0, SableContactQueue.size());
    }
}
