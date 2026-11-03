package com.physicaldestroydeform.collision;

import java.util.Arrays;

/** Bounded histogram of approved first-frame force proxies; NEVER authorizes Q. */
public final class ForceCalibration {
    // [0,16), [16,64), [64,256), [256,1024), [1024,4096),
    // [4096,16384), [16384,65536), [65536,infinity).
    private static final double[] UPPER={16,64,256,1024,4096,16384,65536};
    private final long[] buckets=new long[UPPER.length+1];
    private long approved,saturated;
    private double minPeak=Double.POSITIVE_INFINITY,maxPeak=0;
    private double minRawQ=Double.POSITIVE_INFINITY,maxRawQ=0;

    public void record(double peak,GameImpactCredit.Settings settings) {
        if(settings==null || !Double.isFinite(peak) || peak<0) return;
        // Keep telemetry separate from candidate() and preserve exact gameplay Q.
        double raw=peak<settings.forceEnter()?0:
                settings.forceGain()*Math.max(0,peak-settings.forceFloor());
        approved++;
        if(raw>=settings.maxGroupQ() && settings.maxGroupQ()>0) saturated++;
        minPeak=Math.min(minPeak,peak);
        maxPeak=Math.max(maxPeak,peak);
        minRawQ=Math.min(minRawQ,raw);
        maxRawQ=Math.max(maxRawQ,raw);
        int bucket=0;
        while(bucket<UPPER.length && peak>=UPPER[bucket])bucket++;
        buckets[bucket]++;
    }
    public Snapshot snapshot() {
        return new Snapshot(approved,saturated,approved==0?0:minPeak,maxPeak,
                approved==0?0:minRawQ,maxRawQ,Arrays.toString(buckets));
    }
    public record Snapshot(long approved,long saturated,double minPeak,double maxPeak,
                           double minRawQ,double maxRawQ,String forceBuckets) {}
    public void clear() {
        Arrays.fill(buckets,0);
        approved=saturated=0;
        minPeak=minRawQ=Double.POSITIVE_INFINITY;
        maxPeak=maxRawQ=0;
    }
}
