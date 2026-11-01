package com.physicaldestroydeform.diagnostics;

import com.physicaldestroydeform.collision.FrameMath;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Post-solver native effect samples: 15 raw doubles. Opposing normals and
 * body IDs may be cross-checked for an independently gated game proxy;
 * force scalar and contact points are never elevated to exact physics input. */
public final class NativeCollisionTelemetry {
    public static final boolean ENABLED = true;
    public static final int FIELDS = 15;
    public static final int CAPACITY = 2048;
    /** Confirmed Rust clearCollisions source uses reported.truncate(100).
     * Exactly 100 records is only cap saturation, NOT proven record loss. */
    public static final int NATIVE_EFFECT_MAX_RECORDS = 100;
    private static final ConcurrentLinkedQueue<Contact> QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger SIZE = new AtomicInteger();
    private static final AtomicLong POLLS = new AtomicLong();
    private static final AtomicLong TOTAL = new AtomicLong();
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final AtomicLong MALFORMED = new AtomicLong();
    private static final AtomicLong NATIVE_CAP_SATURATED = new AtomicLong();
    private static final AtomicLong OBSERVER_ERRORS = new AtomicLong();
    public static void observerError(){ OBSERVER_ERRORS.incrementAndGet(); }
    public static long observerErrors(){ return OBSERVER_ERRORS.get(); }

    private NativeCollisionTelemetry() { }
    public static void capture(double[] nativeData) {capture(nativeData,null,-1);}
    public static void capture(double[] nativeData,String dimension,long serverTick) {
        capture(nativeData,dimension,serverTick,null);
    }
    /** Snapshot a body's actual Sable collision-effect consumer pose during the native read.
     * The callback must only read activeSubLevels; it must not mutate the physics state.
     * A missing frame is explicit UNKNOWN, never guessed from the nearest Plot. */
    public static void capture(double[] nativeData,String dimension,long serverTick,
                               IntFunction<OwnerFrame> frameReader) {
        POLLS.incrementAndGet();
        if (nativeData == null || nativeData.length % FIELDS != 0) {
            MALFORMED.incrementAndGet();
            return;
        }
        int n = nativeData.length / FIELDS;
        if(n==NATIVE_EFFECT_MAX_RECORDS)NATIVE_CAP_SATURATED.incrementAndGet();
        TOTAL.addAndGet(n);
        for (int offset = 0; offset < nativeData.length; offset += FIELDS) {
            // A NaN ID would cast to zero and could falsely impersonate a
            // real Sable body. Reject the entire malformed native record.
            double first=nativeData[offset],second=nativeData[offset+1];
            boolean valid=Double.isFinite(first)&&Double.isFinite(second)
                    && first==Math.rint(first)&&second==Math.rint(second)
                    && first>=-1&&first<=Integer.MAX_VALUE
                    && second>=-1&&second<=Integer.MAX_VALUE
                    && !(first==-1 && second==-1) && first!=second
                    && Double.isFinite(nativeData[offset+2]) && nativeData[offset+2]>=0;
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
                    dimension,serverTick,
                    frame(frameReader,(int)nativeData[offset]),frame(frameReader,(int)nativeData[offset+1])));
        }
    }
    private static OwnerFrame frame(IntFunction<OwnerFrame> reader,int id) {
        if(id==-1 || reader==null) return null;
        try { return reader.apply(id); }
        catch(RuntimeException unavailable) {return null;}
    }
    /** Immutable evidence: the pose belongs to the exact Sable effect consumer phase. */
    public record OwnerFrame(UUID uuid, FrameMath.Pose pose) {
        public OwnerFrame {
            if(uuid==null || pose==null || !pose.valid())
                throw new IllegalArgumentException("invalid effect owner frame");
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
    public static long nativeCapSaturatedPolls() {return NATIVE_CAP_SATURATED.get();}
    public static int size() { return SIZE.get(); }
    public record Contact(int idA, int idB, double rawImpactScalar,
            double ax, double ay, double az, double bx, double by, double bz,
            double contactAX, double contactAY, double contactAZ,
            double contactBX, double contactBY, double contactBZ,
            String dimension,long serverTick,OwnerFrame frameA,OwnerFrame frameB) {
        public Contact(int idA,int idB,double rawImpactScalar,
                       double ax,double ay,double az,double bx,double by,double bz,
                       double contactAX,double contactAY,double contactAZ,
                       double contactBX,double contactBY,double contactBZ,
                       String dimension,long serverTick) {
            this(idA,idB,rawImpactScalar,ax,ay,az,bx,by,bz,
                    contactAX,contactAY,contactAZ,contactBX,contactBY,contactBZ,
                    dimension,serverTick,null,null);
        }
        public Contact(int idA,int idB,double rawImpactScalar,
                       double ax,double ay,double az,double bx,double by,double bz,
                       double contactAX,double contactAY,double contactAZ,
                       double contactBX,double contactBY,double contactBZ) {
            this(idA,idB,rawImpactScalar,ax,ay,az,bx,by,bz,
                    contactAX,contactAY,contactAZ,contactBX,contactBY,contactBZ,null,-1,null,null);
        }
    }
}
