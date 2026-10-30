package com.physicaldestroydeform.collision;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** One shared budget per verified owner pair / pre-solver step, regardless of callback count.
 * This version only evaluates budgets: no damage or world mutation. */
public final class ImpactEpisodeLedger {
    public record Key(String dimension,long epoch,String firstOwner,String secondOwner) {
        public Key {
            Objects.requireNonNull(dimension);
            Objects.requireNonNull(firstOwner);
            Objects.requireNonNull(secondOwner);
            if(epoch<0 || firstOwner.compareTo(secondOwner)>=0)
                throw new IllegalArgumentException("unordered/invalid owner pair");
        }
        public static Key of(String dimension,long epoch,String a,String b) {
            if(a==null||b==null||a.equals(b))throw new IllegalArgumentException("same owner");
            return a.compareTo(b)<0?new Key(dimension,epoch,a,b):new Key(dimension,epoch,b,a);
        }
    }
    public static final class Episode {
        private final Set<String> targets=new HashSet<>();
        private long observations;
        private ImpactMath.Result decision;
        public long observations(){return observations;}
        public int uniqueTargets(){return targets.size();}
        public ImpactMath.Result decision(){return decision;}
        private void observe(String targetA,String targetB) {
            observations++;
            targets.add(Objects.requireNonNull(targetA));
            targets.add(Objects.requireNonNull(targetB));
        }
        /** Subsequent callbacks may add unique target blocks, but never add another budget. */
        public ImpactMath.Result approveOnce(ImpactMath.Result candidate) {
            Objects.requireNonNull(candidate);
            if(decision==null || !decision.approved()){decision=candidate;return candidate;}
            return ImpactMath.Result.reject(ImpactMath.Reason.DUPLICATE_EPISODE);
        }
    }
    private final Map<Key,Episode> episodes=new HashMap<>();
    public Episode observe(Key key,String targetA,String targetB) {
        Episode episode=episodes.computeIfAbsent(Objects.requireNonNull(key),ignored->new Episode());
        episode.observe(targetA,targetB);
        return episode;
    }
    public Map<Key,Episode> entries(){return Map.copyOf(episodes);}
    public int size(){return episodes.size();}
}
