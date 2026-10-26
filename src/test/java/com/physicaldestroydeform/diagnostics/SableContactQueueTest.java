package com.physicaldestroydeform.diagnostics;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SableContactQueueTest {
    @Test void capacityAndPreservedResult() {
        SableContactQueue.drain();
        var contact = new SableContactQueue.Contact(1, 2, 3, 4, 5, 6, 7, 8, 9,
                -2.0, true, "FragileBlockCallback", "CollisionResult[tangentMotion=(0.1 0.2 0.3), removeCollision=true]");
        for (int i = 0; i < SableContactQueue.CAPACITY + 2; i++) SableContactQueue.offer(contact);
        assertEquals(SableContactQueue.CAPACITY, SableContactQueue.size());
        var records = SableContactQueue.drain();
        assertEquals(SableContactQueue.CAPACITY, records.size());
        assertTrue(records.get(0).sableResponse().contains("removeCollision=true"));
        assertEquals(-2, records.get(0).signedVelocity());
        assertEquals(0, SableContactQueue.size());
    }
}
