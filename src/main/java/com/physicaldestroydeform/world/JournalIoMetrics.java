package com.physicaldestroydeform.world;

import java.util.Arrays;

/** Bounded timings only. Neither admission, WAL ordering, nor recovery depends on these samples. */
final class JournalIoMetrics {
    enum Phase { WAL_APPEND, WAL_FORCE, WAL_CREATE, RECEIPT_ENCODE, RECEIPT_INDEX,
        COLD_RECEIPT_READ, DURABLE_WRITE, FILE_FORCE, DIRECTORY_FORCE, PUBLISH, CHECKPOINT }
    private static final int WINDOW=128;
    private final long[][] durations=new long[Phase.values().length][WINDOW];
    private final int[] size=new int[Phase.values().length], next=new int[Phase.values().length];
    private final long[] counts=new long[Phase.values().length], max=new long[Phase.values().length];

    void record(Phase phase,long nanos) {
        if(nanos<0)return;
        int i=phase.ordinal();
        durations[i][next[i]]=nanos;next[i]=(next[i]+1)%WINDOW;
        if(size[i]<WINDOW)size[i]++;
        counts[i]++;
        if(nanos>max[i])max[i]=nanos;
    }
    String summary() {
        var result=new StringBuilder();
        for(var phase:Phase.values()) {
            int i=phase.ordinal();
            if(counts[i]==0)continue;
            var sorted=Arrays.copyOf(durations[i],size[i]);Arrays.sort(sorted);
            if(result.length()!=0)result.append(' ');
            result.append(phase).append("={count:").append(counts[i])
                    .append(",p95Nanos:").append(sorted[(size[i]*95+99)/100-1])
                    .append(",maxNanos:").append(max[i]).append('}');
        }
        return result.toString();
    }
}
