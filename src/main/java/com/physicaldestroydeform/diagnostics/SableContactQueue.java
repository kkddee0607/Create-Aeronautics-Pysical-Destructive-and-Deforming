package com.physicaldestroydeform.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.LongAdder;

/** Bounded multiple-producer raw intake, with no logging or world access in callbacks. */
public final class SableContactQueue {
    public static final int CAPACITY = 2048;
    private static final ArrayBlockingQueue<RawObservation> QUEUE = new ArrayBlockingQueue<>(CAPACITY);
    private static final LongAdder TOTAL = new LongAdder();
    private static final LongAdder DROPPED = new LongAdder();
    private static final LongAdder CAPTURE_ERRORS = new LongAdder();
    private static final LongAdder WRAPPED_ORIGINAL = new LongAdder();
    private static final LongAdder WRAPPED_NULL = new LongAdder();
    private SableContactQueue() {}
    public static void registered(boolean originalPresent) {
        (originalPresent ? WRAPPED_ORIGINAL : WRAPPED_NULL).increment();
    }
    public static void captureError() { CAPTURE_ERRORS.increment(); }
    public static void offer(RawObservation observation) {
        Objects.requireNonNull(observation, "observation");
        TOTAL.increment();
        if (!QUEUE.offer(observation)) DROPPED.increment();
    }
    /** Single server-tick consumer; do not use this queue for physics decisions. */
    public static List<RawObservation> drain() {
        var values = new ArrayList<RawObservation>();
        QUEUE.drainTo(values);
        return values;
    }
    public static long total() { return TOTAL.sum(); }
    public static long dropped() { return DROPPED.sum(); }
    public static long captureErrors() { return CAPTURE_ERRORS.sum(); }
    public static long wrappedOriginal() { return WRAPPED_ORIGINAL.sum(); }
    public static long wrappedNull() { return WRAPPED_NULL.sum(); }
    public static int size() { return QUEUE.size(); }
}
