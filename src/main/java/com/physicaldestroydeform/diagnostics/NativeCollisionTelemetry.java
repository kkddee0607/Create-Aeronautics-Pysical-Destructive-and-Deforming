package com.physicaldestroydeform.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Actual Rapier native collision array records from Sable 2.0.6.
 * The 15-double record layout was verified from RapierPhysicsPipeline bytecode.
 * The raw impact scalar is deliberately NOT called velocity, energy or impulse.
 * One sample per native record, no fabricated game collisions.
 */
public final class NativeCollisionTelemetry {
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("pdd.debugCollisions", "true"));
    public static final int FIELDS = 15;
    public static final int CAPACITY = 2048;
    private static final ConcurrentLinkedQueue<Contact> QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger SIZE = new AtomicInteger();
    private static final AtomicLong POLLS = new AtomicLong();
    private static final AtomicLong TOTAL = new AtomicLong();
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final AtomicLong MALFORMED = new AtomicLong();

    private NativeCollisionTelemetry() { }

    public static void capture(double[] nativeData) {
        POLLS.incrementAndGet();
        if (nativeData == null || nativeData.length % FIELDS != 0) {
            MALFORMED.incrementAndGet();
            return;
        }
        int n = nativeData.length / FIELDS;
        TOTAL.addAndGet(n);
        for (int offset = 0; offset < nativeData.length; offset += FIELDS) {
            int old;
            do {
                old = SIZE.get();
                if (old >= CAPACITY) {
                    DROPPED.addAndGet((nativeData.length-offset)/FIELDS);
                    return;
                }
            } while (!SIZE.compareAndSet(old,old+1));
            QUEUE.offer(new Contact((int)nativeData[offset], (int)nativeData[offset+1],
                    nativeData[offset+2],
                    nativeData[offset+3], nativeData[offset+4], nativeData[offset+5],
                    nativeData[offset+6], nativeData[offset+7], nativeData[offset+8],
                    nativeData[offset+9], nativeData[offset+10], nativeData[offset+11],
                    nativeData[offset+12], nativeData[offset+13], nativeData[offset+14]));
        }
    }

    public static List<Contact> drain() {
        List<Contact> values = new ArrayList<>();
        Contact c;
        while ((c=QUEUE.poll())!=null) { SIZE.decrementAndGet(); values.add(c); }
        return values;
    }
    public static long polls() { return POLLS.get(); }
    public static long total() { return TOTAL.get(); }
    public static long dropped() { return DROPPED.get(); }
    public static long malformed() { return MALFORMED.get(); }
    public static int size() { return SIZE.get(); }

    public record Contact(int idA, int idB, double rawImpactScalar,
            double ax, double ay, double az, double bx, double by, double bz,
            double contactAX, double contactAY, double contactAZ,
            double contactBX, double contactBY, double contactBZ) { }
}
