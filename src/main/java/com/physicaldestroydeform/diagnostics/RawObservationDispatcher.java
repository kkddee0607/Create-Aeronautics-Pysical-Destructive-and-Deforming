package com.physicaldestroydeform.diagnostics;

import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/** Server-tick entry point, isolated from the Sable callback thread. */
public final class RawObservationDispatcher {
    private static volatile RawObservationConsumer consumer = ignored -> {};
    private static final LongAdder CONSUMED = new LongAdder();
    private static final LongAdder CONSUMER_ERRORS = new LongAdder();
    private RawObservationDispatcher() {}

    public static void setConsumer(RawObservationConsumer next) {
        consumer = Objects.requireNonNull(next, "consumer");
    }
    public static void reset() { consumer = ignored -> {}; }
    public static void dispatch(Iterable<RawObservation> observations) {
        RawObservationConsumer target = consumer;
        for (RawObservation observation : observations) {
            try { target.accept(observation); CONSUMED.increment(); }
            catch (RuntimeException ex) { CONSUMER_ERRORS.increment(); }
        }
    }
    public static long consumed() { return CONSUMED.sum(); }
    public static long consumerErrors() { return CONSUMER_ERRORS.sum(); }
}
