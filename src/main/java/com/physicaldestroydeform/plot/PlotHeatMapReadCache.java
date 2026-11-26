package com.physicaldestroydeform.plot;

import com.physicaldestroydeform.diagnostics.RogTrace;
import com.physicaldestroydeform.diagnostics.RogTrace.Stage;
import java.util.function.Supplier;

/** Per-native-manager, server-thread cache of a DENIAL only. A healthy sample is
 * never stored as a grant, even within one tick. */
public final class PlotHeatMapReadCache {
    public record Pending(boolean initialized,boolean complete,int flood,int removed,int starts,int splitCells) {}

    /** Implemented by the HeatMap manager witness, not a global owner-key map. */
    public interface Witness { PlotHeatMapReadCache pdd$heatMapReadCache(); }

    private volatile Pending cached;
    private long tick=-1;
    private long mutationSequence;
    private int nativeDepth;

    public void beginMutation(){
        if(cached!=null)cached=null;
        mutationSequence++;nativeDepth++;
    }
    public void endMutation(){
        if(cached!=null)cached=null;
        mutationSequence++;
        if(nativeDepth>0)nativeDepth--;
    }
    public Pending pending(long now,boolean forceLive,Supplier<Pending> read){
        // Read the reference once: even a foreign-thread invalidation cannot
        // turn a cache hit into a null (healthy) result in the caller.
        var prior=cached;
        if(!forceLive&&nativeDepth==0&&tick==now&&prior!=null){if(RogTrace.sample(Stage.R09,"CACHED_DENIAL"))RogTrace.detail(Stage.R09,"CACHED_DENIAL","tick={} mutationSequence={} nativeDepth={} pending={}",now,mutationSequence,nativeDepth,prior);return prior;}
        // Clear before the read. A failed observation cannot restore a stale denial.
        if(prior!=null)cached=null;
        long sequence=mutationSequence;
        var pending=read.get();
        if(RogTrace.sample(Stage.R09,pending==null?"LIVE_CLEAR":"LIVE_DENIAL"))RogTrace.detail(Stage.R09,pending==null?"LIVE_CLEAR":"LIVE_DENIAL","tick={} forceLive={} mutationSequence={} nativeDepth={} pending={}",now,forceLive,mutationSequence,nativeDepth,pending);
        if(nativeDepth==0&&mutationSequence==sequence){cached=pending;tick=now;}
        return pending;
    }
}
