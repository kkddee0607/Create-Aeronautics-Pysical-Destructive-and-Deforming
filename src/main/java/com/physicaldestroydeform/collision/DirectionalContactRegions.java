package com.physicaldestroydeform.collision;

import java.util.*;
import static com.physicaldestroydeform.collision.FrameMath.Vec;

/** Bounded, server-thread Pair-local observation history. Direction is not
 * collision authority: callers must separately prove R2 and approach/gap.
 * ponytail: indistinguishable same-direction patches share a conservative
 * track until independently verified spatial continuity is available. */
final class DirectionalContactRegions {
    static final int MAX_TRACKS=16, MAX_WITNESSES=2048;
    private static final double ENTER=Math.cos(Math.toRadians(30)), KEEP=Math.cos(Math.toRadians(40));
    record Direction(Vec a,Vec b) {
        Direction {a=a.unit();b=b.unit();}
        boolean near(Direction other,double cos){return a.dot(other.a)>=cos-1e-12&&b.dot(other.b)>=cos-1e-12;}
    }
    record WorldFootprint(String owner,Vec inward,double plane,List<GameImpactCredit.Target> targets) {}
    enum Phase { UNKNOWN, ARMED, ACTIVE_SPENT, COOLING }
    static final class Track {
        final long id;
        final Direction anchor;
        Phase phase=Phase.UNKNOWN;
        boolean observed,consumed;
        long lastSeen=-1,lastGap=-1,lastCredit=-1,ambiguousTick=-1;
        int gaps;
        WorldFootprint footprint;
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
    boolean eligible(Track t,boolean independentPreGap,boolean fullPairArmed){
        if(t==null||t.ambiguousTick==t.lastSeen)return false;
        return fullPairArmed||t.phase==Phase.ARMED||(!t.observed&&!t.consumed&&independentPreGap);
    }
    void contact(Track t,boolean authorized){
        if(t==null)return;
        t.observed=true;t.consumed|=authorized;t.phase=Phase.ACTIVE_SPENT;t.gaps=0;t.lastGap=-1;
        if(authorized)t.lastCredit=t.lastSeen;
    }
    void separated(Track t,long tick){
        if(t==null||tick<=t.lastSeen||tick<=t.lastGap)return;
        t.gaps=t.lastGap==tick-1?Math.min(2,t.gaps+1):1;t.lastGap=tick;
        t.phase=t.gaps>=2?Phase.ARMED:Phase.COOLING;
    }
    void unknown(Track t,long tick){
        if(t==null)return;
        t.phase=Phase.UNKNOWN;t.gaps=0;t.lastGap=-1;
        // Unknown samples cannot fabricate first-observation authorization.
        t.observed=true;
    }
    void fullSeparation(long tick){for(var t:tracks)separated(t,tick);}
    void incomplete(long tick){for(var t:tracks)unknown(t,tick);}
}
