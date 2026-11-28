package com.physicaldestroydeform.collision;

import java.util.*;
import static com.physicaldestroydeform.collision.FrameMath.Vec;

/** Bounded, server-thread Pair-local observation history. Direction is not
 * collision authority: callers must separately prove R2 and approach/gap.
 * Spatial probes preserve latches; only verified surface/gap can admit a new track. */
final class DirectionalContactRegions {
    static final int MAX_TRACKS=16, MAX_WITNESSES=2048;
    private static final double ENTER=Math.cos(Math.toRadians(30)), KEEP=Math.cos(Math.toRadians(40));
    record Direction(Vec a,Vec b) {
        Direction {a=a.unit();b=b.unit();}
        boolean near(Direction other,double cos){return a.dot(other.a)>=cos-1e-12&&b.dot(other.b)>=cos-1e-12;}
    }
    record WorldFootprint(String owner,Vec inward,double plane,List<GameImpactCredit.Target> targets) {}
    enum Phase { UNKNOWN, ARMED, OBSERVED_UNSPENT, PENDING_GRANT, ACTIVE_SPENT, COOLING }
    static final class Track {
        final long id;
        final Direction anchor;
        Phase phase=Phase.UNKNOWN;
        boolean observed,consumed,retry;
        long pendingTick=-1;
        long lastSeen=-1,lastGap=-1,lastCredit=-1,ambiguousTick=-1;
        int gaps;
        WorldFootprint footprint;
        DcrtSpatialFootprint.Probe probe;
        DcrtSpatialFootprint.Surface surface;
        Track(long id,Direction direction){this.id=id;anchor=direction;}
    }
    private final List<Track> tracks=new ArrayList<>();
    private long sequence;
    List<Track> tracks(){return Collections.unmodifiableList(tracks);}
    /** Complete-link, deterministic direction partitions. A chain cannot join
     * two incompatible directions. No unbounded search over the native feed. */
    static List<List<Integer>> cluster(List<Direction> directions){
        if(directions.size()>MAX_WITNESSES)return List.of();
        List<List<Integer>> out=new ArrayList<>();
        // Preserve native order within a group; sort group anchors deterministically.
        List<Integer> order=new ArrayList<>();for(int i=0;i<directions.size();i++)if(directions.get(i)!=null)order.add(i);
        order.sort(Comparator.comparingDouble((Integer i)->directions.get(i).a.x())
                .thenComparingDouble(i->directions.get(i).a.y()).thenComparingDouble(i->directions.get(i).a.z())
                .thenComparingDouble(i->directions.get(i).b.x()).thenComparingDouble(i->directions.get(i).b.y()).thenComparingDouble(i->directions.get(i).b.z()));
        var assigned=new HashMap<Direction,List<Integer>>();
        var distinct=new IdentityHashMap<List<Integer>,Set<Direction>>();int work=0;
        for(int i:order){
            var direction=directions.get(i);var existing=assigned.get(direction);
            if(existing!=null){existing.add(i);continue;}
            List<Integer> match=null;
            for(var g:out){boolean compatible=true;for(var d:distinct.get(g)){if(++work>262144)return List.of();if(!direction.near(d,ENTER)){compatible=false;break;}}
                if(compatible){match=g;break;}}
            if(match==null){if(out.size()>=MAX_TRACKS)return List.of();match=new ArrayList<>();out.add(match);distinct.put(match,new HashSet<>());}
            match.add(i);distinct.get(match).add(direction);assigned.put(direction,match);
        }
        for(var group:out)group.sort(Integer::compareTo);
        return out;
    }
    Track observe(Direction d,long tick,boolean complete,boolean fullPairArmed){
        if(!complete||d==null){for(var track:tracks)unknown(track,tick);return null;}
        Track match=null;
        for(var track:tracks)if(track.anchor.near(d,KEEP)){
            if(match!=null){match.ambiguousTick=tick;track.ambiguousTick=tick;unknown(match,tick);unknown(track,tick);return null;}
            match=track;
        }
        if(match==null){if(tracks.size()>=MAX_TRACKS)return null;match=new Track(++sequence,d);tracks.add(match);}
        if(match.lastSeen==tick){match.ambiguousTick=tick;unknown(match,tick);return null;}
        match.lastSeen=tick;
        if(fullPairArmed)match.phase=Phase.ARMED;
        return match;
    }
    // A verified full-Pair clearance is the one circumstance in which old
    // spatial IDs may be retired. The sequence and issued ledger are preserved.
    void resetForFullPair(){if(tracks.stream().noneMatch(t->t.pendingTick>=0))tracks.clear();}
    Track newTrack(Direction d,long tick,boolean fullPairArmed){
        if(tracks.size()>=MAX_TRACKS)return null;
        var t=new Track(++sequence,d);tracks.add(t);touch(t,tick,fullPairArmed);return t;
    }
    boolean touch(Track t,long tick,boolean fullPairArmed){
        if(t.pendingTick>=0||t.lastSeen==tick){t.ambiguousTick=tick;unknown(t,tick);return false;}
        t.lastSeen=tick;if(fullPairArmed)t.phase=Phase.ARMED;return true;
    }
    boolean directionMatches(Track t,Direction d){return t.anchor.near(d,KEEP);}
    boolean eligible(Track t,boolean independentPreGap,boolean fullPairArmed){
        if(t==null||t.ambiguousTick==t.lastSeen)return false;
        return t.pendingTick<0&&(fullPairArmed||t.phase==Phase.ARMED||
                (independentPreGap&&(t.retry||!t.observed&&!t.consumed)));
    }
    void contact(Track t,boolean authorized){
        if(t==null)return;
        if(authorized){reserve(t);finish(t,t.lastSeen,true);}
        else observed(t,false);
    }
    void observed(Track t,boolean retryCandidate){
        if(t==null||t.pendingTick>=0)return;
        t.observed=true;t.retry=retryCandidate;t.gaps=0;t.lastGap=-1;
        t.phase=t.consumed&&!retryCandidate?Phase.ACTIVE_SPENT:Phase.OBSERVED_UNSPENT;
    }
    void reserve(Track t){
        if(t==null||t.pendingTick>=0)throw new IllegalStateException("regional reservation already pending");
        t.observed=true;t.pendingTick=t.lastSeen;t.phase=Phase.PENDING_GRANT;
        t.gaps=0;t.lastGap=-1;
    }
    void finish(Track t,long tick,boolean sealed){
        if(t==null||t.pendingTick!=tick)return;
        t.pendingTick=-1;t.retry=!sealed;
        if(sealed){t.consumed=true;t.lastCredit=tick;t.phase=Phase.ACTIVE_SPENT;}
        else t.phase=Phase.OBSERVED_UNSPENT;
    }
    void separated(Track t,long tick){
        if(t==null||t.pendingTick>=0||tick<=t.lastSeen||tick<=t.lastGap)return;
        t.gaps=t.lastGap==tick-1?Math.min(2,t.gaps+1):1;t.lastGap=tick;
        t.phase=t.gaps>=2?Phase.ARMED:Phase.COOLING;
    }
    void unknown(Track t,long tick){
        if(t==null)return;
        t.phase=Phase.UNKNOWN;t.retry=false;t.gaps=0;t.lastGap=-1;
        // Last verified geometry remains a conservative routing hint only.
        // It must be revalidated in the current capture for any disjoint proof.
        // Unknown samples cannot fabricate first-observation authorization.
        t.observed=true;
    }
    void fullSeparation(long tick){for(var t:tracks)separated(t,tick);}
    void incomplete(long tick){for(var t:tracks)unknown(t,tick);}
}
