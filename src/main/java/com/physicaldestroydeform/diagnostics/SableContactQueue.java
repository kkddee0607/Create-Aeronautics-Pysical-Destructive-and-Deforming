package com.physicaldestroydeform.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded multi-thread producer queue with immutable value-only observations. */
public final class SableContactQueue {
    public static final int CAPACITY = 2048;
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("pdd.debugCollisions", "true"));
    private static final ConcurrentLinkedQueue<Contact> QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger SIZE = new AtomicInteger();
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final AtomicLong TOTAL = new AtomicLong();
    private SableContactQueue() {}
    public static void offer(Contact contact) {
        TOTAL.incrementAndGet();
        int size;
        do {
            size = SIZE.get();
            if (size >= CAPACITY) { DROPPED.incrementAndGet(); return; }
        } while (!SIZE.compareAndSet(size, size + 1));
        QUEUE.add(contact);
    }
    public static List<Contact> drain() {
        List<Contact> result = new ArrayList<>();
        Contact current;
        while ((current = QUEUE.poll()) != null) {
            SIZE.decrementAndGet();
            result.add(current);
        }
        return result;
    }
    public static long total() { return TOTAL.get(); }
    public static long dropped() { return DROPPED.get(); }
    public static int size() { return SIZE.get(); }
    public record Contact(int x, int y, int z, int otherX, int otherY, int otherZ,
                          double impactX, double impactY, double impactZ, double signedVelocity,
                          boolean hasOther, String callbackType, String sableResponse) {}
}
