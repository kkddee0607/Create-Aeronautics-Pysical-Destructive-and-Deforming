package com.physicaldestroydeform.collision;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Cheap, allocation-bounded indexing of the 15-double native effect feed.
 * The record body IDs are NOT stable owner IDs. Owner/generation validation is
 * a separate server-thread stage. A force record is not solver impulse data.
 */
public final class NativeEffectGroups {
    private NativeEffectGroups() {}
    public static final int MAX_RECORDS = 2048;
    public record RuntimePair(int lowId, int highId) {
        public RuntimePair {
            if (lowId < -1 || lowId >= highId || highId < 0)
                throw new IllegalArgumentException("invalid runtime body pair");
        }
        public static RuntimePair of(int a,int b) {
            if (a == b) throw new IllegalArgumentException("same native ID");
            return a < b ? new RuntimePair(a,b) : new RuntimePair(b,a);
        }
    }
    public record Group(String dimension, long tick, RuntimePair runtimePair,
                        int originalRecordCount, double forcePeak,
                        List<GameImpactCredit.ObservedForce> samples) {
        public Group { samples=List.copyOf(samples); }
    }
    public record Batch(List<Group> groups, int totalRecords, int invalidRecords,
                        int discardedRecords, boolean malformedArray) {
        public Batch { groups=List.copyOf(groups); }
    }
    private static final class MutableGroup {
        final List<GameImpactCredit.ObservedForce> samples = new ArrayList<>();
        int total;
        double peak;
        void accept(GameImpactCredit.ObservedForce record) {
            total++;
            peak=Math.max(peak,record.force());
            if(samples.size()<4) samples.add(record);
        }
    }
    public static Batch aggregate(String dimension,long tick,double[] raw) {
        if(dimension==null || dimension.isBlank() || tick<0 ||raw==null||raw.length%15!=0)
            return new Batch(List.of(),0,0,0,true);
        final int count=raw.length/15;
        final int limit=Math.min(count,MAX_RECORDS);
        final Map<RuntimePair,MutableGroup> grouped=new HashMap<>();
        int invalid=0;
        for(int i=0;i<limit;i++) {
            int off=i*15;
            boolean valid=true;
            for(int j=0;j<15;j++) valid &= Double.isFinite(raw[off+j]);
            double a=raw[off],b=raw[off+1],f=raw[off+2];
            valid&=a==Math.rint(a)&&b==Math.rint(b)&&a>=-1&&b>=-1
                    &&a<=Integer.MAX_VALUE&&b<=Integer.MAX_VALUE&&a!=b&&f>=0;
            if(!valid){invalid++;continue;}
            var key=RuntimePair.of((int)a,(int)b);
            grouped.computeIfAbsent(key,k->new MutableGroup()).accept(
                    new GameImpactCredit.ObservedForce((int)a,(int)b,f,i));
        }
        var result=new ArrayList<Group>(grouped.size());
        grouped.forEach((pair,m)->result.add(new Group(dimension,tick,pair,m.total,m.peak,m.samples)));
        result.sort(java.util.Comparator.comparingInt((Group g)->g.runtimePair().lowId())
                .thenComparingInt(g->g.runtimePair().highId()));
        return new Batch(result,count,invalid,count-limit,false);
    }
}
