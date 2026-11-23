package com.physicaldestroydeform.diagnostics;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import org.slf4j.Logger;

/** Fixed-size, server-thread-only instrumentation. No background threads, GC or heap dump. */
public final class RuntimeDiagnostics {
    static final long INTERVAL_NS=10_000_000_000L;
    private static final int SAMPLES=512;
    private final Window total=new Window(SAMPLES), bridge=new Window(SAMPLES);
    private long lastReport;

    public void reset(long now){total.reset();bridge.reset();lastReport=now;}
    public void recordTick(long nanos){total.record(nanos);}
    public void recordBridge(long nanos){bridge.record(nanos);}
    public boolean reportDue(long now){
        if(now-lastReport<INTERVAL_NS)return false;
        lastReport=now;return true;
    }
    public void log(Logger logger){
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
        logger.info("[PDD-MEMORY] heapUsed={} heapCommitted={} heapMax={} gcCount={} gcMillis={} nativeMemory=UNMEASURED",
                runtime.totalMemory()-runtime.freeMemory(),runtime.totalMemory(),runtime.maxMemory(),gcCount,gcMillis);
        var t=total.snapshot();var b=bridge.snapshot();
        logger.info("[PDD-PERFORMANCE] samples={} p50Nanos={} p95Nanos={} p99Nanos={} bridgeSamples={} bridgeP50Nanos={} bridgeP95Nanos={} bridgeP99Nanos={} mode={}",
                t.count(),t.p50(),t.p95(),t.p99(),b.count(),b.p50(),b.p95(),b.p99(),DiagnosticMode.current());
    }
    record Percentiles(int count,long p50,long p95,long p99){}
    static final class Window {
        private final long[] values;
        private int size,next;
        Window(int capacity){if(capacity<1)throw new IllegalArgumentException("capacity");values=new long[capacity];}
        void record(long nanos){if(nanos<0)return;values[next]=nanos;next=(next+1)%values.length;if(size<values.length)size++;}
        void reset(){size=0;next=0;}
        Percentiles snapshot(){
            if(size==0)return new Percentiles(0,0,0,0);
            long[] sorted=Arrays.copyOf(values,size);Arrays.sort(sorted);
            return new Percentiles(size,at(sorted,50),at(sorted,95),at(sorted,99));
        }
        private static long at(long[] sorted,int percentile){return sorted[(sorted.length*percentile+99)/100-1];}
    }
}
