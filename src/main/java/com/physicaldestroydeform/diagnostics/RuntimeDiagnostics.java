package com.physicaldestroydeform.diagnostics;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.EnumMap;
import org.slf4j.Logger;

/** Fixed-size, server-thread-only instrumentation. No background threads, GC or heap dump. */
public final class RuntimeDiagnostics {
    static final long INTERVAL_NS=10_000_000_000L;
    private static final int SAMPLES=512;
    public enum Phase { LINEAGE, CAPTURE, BRIDGE, DISPATCH, STRESS, WORLD_COMMIT, PLOT_COMMIT, DIAGNOSTICS }
    private final Window total=new Window(SAMPLES), bridge=new Window(SAMPLES);
    private final EnumMap<Phase,Window> phases=new EnumMap<>(Phase.class);
    public RuntimeDiagnostics(){for(var phase:Phase.values())phases.put(phase,new Window(SAMPLES));}
    private long lastReport;

    public void reset(long now){total.reset();bridge.reset();for(var window:phases.values())window.reset();lastReport=now;}
    public void recordTick(long nanos){total.record(nanos);}
    public void recordBridge(long nanos){bridge.record(nanos);}
    public void recordPhase(Phase phase,long nanos){phases.get(phase).record(nanos);}
    Percentiles phaseSnapshot(Phase phase){return phases.get(phase).snapshot();}
    public boolean reportDue(long now){
        if(now-lastReport<INTERVAL_NS)return false;
        lastReport=now;return true;
    }
    public void log(Logger logger){
        if(!LogSelection.enabled(LogSelection.Channel.PERF))return;
        Runtime runtime=Runtime.getRuntime();
        long gcCount=-1,gcMillis=-1;
        try{
            long count=0,time=0;
            boolean hasCount=false,hasTime=false;
            for(var gc:ManagementFactory.getGarbageCollectorMXBeans()){
                long c=gc.getCollectionCount(),t=gc.getCollectionTime();
                if(c>=0){count+=c;hasCount=true;}
                if(t>=0){time+=t;hasTime=true;}
            }
            if(hasCount)gcCount=count;if(hasTime)gcMillis=time;
        }catch(RuntimeException|LinkageError unavailable){/* VM may omit management support. */}
        long direct=-1,mapped=-1;
        try{for(var pool:ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class)){
            if(pool.getName().equals("direct"))direct=pool.getMemoryUsed();
            else if(pool.getName().equals("mapped"))mapped=pool.getMemoryUsed();
        }}catch(RuntimeException|LinkageError unavailable){/* optional VM counter */}
        logger.info("[PDD-MEMORY] heapUsed={} heapCommitted={} heapMax={} gcCount={} gcMillis={} directBufferBytes={} mappedBufferBytes={} nativeMemory=UNMEASURED",
                runtime.totalMemory()-runtime.freeMemory(),runtime.totalMemory(),runtime.maxMemory(),gcCount,gcMillis,direct,mapped);
        var t=total.snapshot();var b=bridge.snapshot();
        logger.info("[PDD-PERFORMANCE] samples={} p50Nanos={} p95Nanos={} p99Nanos={} bridgeSamples={} bridgeP50Nanos={} bridgeP95Nanos={} bridgeP99Nanos={} maxNanos={} bridgeMaxNanos={} mode={}",
                t.count(),t.p50(),t.p95(),t.p99(),b.count(),b.p50(),b.p95(),b.p99(),t.max(),b.max(),DiagnosticMode.current());
        for(var entry:phases.entrySet()){
            var p=entry.getValue().snapshot();
            if(p.count()!=0)logger.info("[PDD-PHASE-PERF] phase={} samples={} p50Nanos={} p95Nanos={} p99Nanos={} maxNanos={}",
                    entry.getKey(),p.count(),p.p50(),p.p95(),p.p99(),p.max());
        }
    }
    record Percentiles(int count,long p50,long p95,long p99,long max){}
    static final class Window {
        private final long[] values;
        private int size,next;
        private long maxSinceReset;
        Window(int capacity){if(capacity<1)throw new IllegalArgumentException("capacity");values=new long[capacity];}
        void record(long nanos){if(nanos<0)return;values[next]=nanos;next=(next+1)%values.length;if(size<values.length)size++;if(nanos>maxSinceReset)maxSinceReset=nanos;}
        void reset(){size=0;next=0;maxSinceReset=0;}
        Percentiles snapshot(){
            if(size==0)return new Percentiles(0,0,0,0,0);
            long[] sorted=Arrays.copyOf(values,size);Arrays.sort(sorted);
            return new Percentiles(size,at(sorted,50),at(sorted,95),at(sorted,99),maxSinceReset);
        }
        private static long at(long[] sorted,int percentile){return sorted[(sorted.length*percentile+99)/100-1];}
    }
}
