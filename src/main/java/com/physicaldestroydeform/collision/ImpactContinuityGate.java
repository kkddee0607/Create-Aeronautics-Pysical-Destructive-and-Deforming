package com.physicaldestroydeform.collision;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** One budget for a sustained body-pair contact. A full contact-free server tick
 * must precede a new budget; callback count and substep count never mint credit. */
public final class ImpactContinuityGate {
    public record Pair(String dimension,String a,String b) {
        public Pair {Objects.requireNonNull(dimension);Objects.requireNonNull(a);Objects.requireNonNull(b);
            if(a.compareTo(b)>=0)throw new IllegalArgumentException("unordered pair");}
        public static Pair from(ImpactEpisodeLedger.Key key) {
            return new Pair(key.dimension(),key.firstOwner(),key.secondOwner());
        }
    }
    private static final class State { long seenTick; boolean credited; State(long tick){seenTick=tick;} }
    private final Map<Pair,State> recent=new HashMap<>();
    public ImpactMath.Result observe(Pair pair,long tick,ImpactMath.Result result) {
        Objects.requireNonNull(pair); Objects.requireNonNull(result);
        State state=recent.get(pair);
        if(state==null) { state=new State(tick);recent.put(pair,state); }
        else if(tick<state.seenTick) return ImpactMath.Result.reject(ImpactMath.Reason.STALE_SNAPSHOT);
        else if(tick>state.seenTick+1)state.credited=false;
        state.seenTick=tick;
        if(!result.approved())return result;
        if(state.credited)return ImpactMath.Result.reject(ImpactMath.Reason.DUPLICATE_EPISODE);
        state.credited=true;
        return result;
    }
    public void prune(long currentTick) {
        recent.entrySet().removeIf(entry->entry.getValue().seenTick<currentTick-2);
    }
    public void clear(){recent.clear();}
}
