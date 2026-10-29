package com.physicaldestroydeform.diagnostics;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RawDispatcherTest {
    @Test void preservesCapturedValuesAndDispatchesToConsumer() {
        RawObservation sample = new RawObservation(123L, "callback", "UNKNOWN", "UNVERIFIED",
            "minecraft:stone", "NONE", false, 10, 11, 12, false, 0, 0, 0,
            1.0, 2.0, 3.0, -4.0, false);
        AtomicInteger seen = new AtomicInteger();
        RawObservationDispatcher.setConsumer(value -> {
            assertSame(sample, value); assertEquals(-4.0, value.signedVelocity()); seen.incrementAndGet();
        });
        try { RawObservationDispatcher.dispatch(List.of(sample)); assertEquals(1, seen.get()); }
        finally { RawObservationDispatcher.reset(); }
    }
    @Test void queueIsStrictlyBoundedAndCountsDrops() {
        SableContactQueue.drain();
        long prior = SableContactQueue.dropped();
        for (int i = 0; i < SableContactQueue.CAPACITY + 8; i++) {
            SableContactQueue.offer(new RawObservation(i, "source", "UNKNOWN", "UNVERIFIED",
                "minecraft:stone", "NONE", false, 0,0,0,false,0,0,0,0,0,0,0,false));
        }
        assertEquals(SableContactQueue.CAPACITY, SableContactQueue.size());
        assertEquals(8, SableContactQueue.dropped() - prior);
        SableContactQueue.drain();
    }
}
