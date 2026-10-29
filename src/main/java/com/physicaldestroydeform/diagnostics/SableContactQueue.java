package com.physicaldestroydeform.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded, always-on, multiple-producer raw intake. Logging is independently optional. */
public final class SableContactQueue {
    public static final int CAPACITY = 2048;
    private static final ConcurrentLinkedQueue<RawObservation> QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger SIZE = new AtomicInteger();
    private static final AtomicLong TOTAL = new AtomicLong();
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final AtomicLong CAPTURE_ERRORS = new AtomicLong();
    private static final AtomicLong WRAPPED_ORIGINAL = new AtomicLong();
    private static final AtomicLong WRAPPED_NULL = new AtomicLong();

    private SableContactQueue() {}
    public static void registered(boolean originalPresent) {
        (originalPresent ? WRAPPED_ORIGINAL : WRAPPED_NULL).incrementAndGet();
    }
    public static void captureError() { CAPTURE_ERRORS.incrementAndGet(); }
    public static void offer(RawObservation contact) {
        Objects.requireNonNull(contact, "contact");
        TOTAL.incrementAndGet();
        int size;
        do {
            size = SIZE.get();
            if (size >= CAPACITY) { DROPPED.incrementAndGet(); return; }
        } while (!SIZE.compareAndSet(size, size + 1));
        QUEUE.add(contact);
    }
    public static List<RawObservation> drain() {
        List<RawObservation> result = new ArrayList<>();
        RawObservation current;
        while ((current = QUEUE.poll()) != null) {
            SIZE.decrementAndGet();
            result.add(current);
        }
        return result;
    }
    public static long total() { return TOTAL.get(); }
    public static long dropped() { return DROPPED.get(); }
    public static long captureErrors() { return CAPTURE_ERRORS.get(); }
    public static long wrappedOriginal() { return WRAPPED_ORIGINAL.get(); }
    public static long wrappedNull() { return WRAPPED_NULL.get(); }
    public static int size() { return SIZE.get(); }
}
