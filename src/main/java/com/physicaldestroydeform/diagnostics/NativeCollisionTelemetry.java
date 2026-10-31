package com.physicaldestroydeform.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Post-solver native effect samples: 15 raw doubles. Opposing normals and
 * body IDs may be cross-checked for an independently gated game proxy;
 * force scalar and contact points are never elevated to exact physics input. */
public final class NativeCollisionTelemetry {
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("pdd.debugCollisions", "true"))
            || Boolean.parseBoolean(System.getProperty("pdd.proxy.enabled", "false"));
    public static final int FIELDS = 15;
    public static final int CAPACITY = 2048;
    private static final ConcurrentLinkedQueue<Contact> QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger SIZE = new AtomicInteger();
    private static final AtomicLong POLLS = new AtomicLong();
    private static final AtomicLong TOTAL = new AtomicLong();
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final AtomicLong MALFORMED = new AtomicLong();

    private NativeCollisionTelemetry() { }
    public static void capture(double[] nativeData) {capture(nativeData,null,-1);}
    public static void capture(double[] nativeData,String dimension,long serverTick) {
        POLLS.incrementAndGet();
        if (nativeData == null || nativeData.length % FIELDS != 0) {
            MALFORMED.incrementAndGet();
            return;
        }
        int n = nativeData.length / FIELDS;
        TOTAL.addAndGet(n);
        for (int offset = 0; offset < nativeData.length; offset += FIELDS) {
            // A NaN ID would cast to zero and could falsely impersonate a
            // real Sable body. Reject the entire malformed native record.
            double first=nativeData[offset],second=nativeData[offset+1];
            boolean valid=Double.isFinite(first)&&Double.isFinite(second)
                    && first==Math.rint(first)&&second==Math.rint(second)
                    && first>=Integer.MIN_VALUE&&first<=Integer.MAX_VALUE
                    && second>=Integer.MIN_VALUE&&second<=Integer.MAX_VALUE;
            for(int i=2;i<FIELDS;i++)valid &= Double.isFinite(nativeData[offset+i]);
            if(!valid){MALFORMED.incrementAndGet();continue;}
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
                    nativeData[offset+12], nativeData[offset+13], nativeData[offset+14],
                    dimension,serverTick));
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
            double contactBX, double contactBY, double contactBZ,
            String dimension,long serverTick) {
        public Contact(int idA,int idB,double rawImpactScalar,
                       double ax,double ay,double az,double bx,double by,double bz,
                       double contactAX,double contactAY,double contactAZ,
                       double contactBX,double contactBY,double contactBZ) {
            this(idA,idB,rawImpactScalar,ax,ay,az,bx,by,bz,
                    contactAX,contactAY,contactAZ,contactBX,contactBY,contactBZ,null,-1);
        }
    }
}
