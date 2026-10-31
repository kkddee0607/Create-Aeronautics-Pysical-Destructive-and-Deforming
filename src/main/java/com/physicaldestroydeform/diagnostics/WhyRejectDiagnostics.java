package com.physicaldestroydeform.diagnostics;

import com.physicaldestroydeform.collision.ImpactEpisodeLedger;
import com.physicaldestroydeform.collision.ImpactMath;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Bounded per-episode evidence for G-MATH-01. Callback target pairs are NOT
 * Rapier contact/manifold pairs: native normals identify a body pair only.
 * Never affects the collision callback, an approval decision or world state.
 */
public final class WhyRejectDiagnostics {
    public static final int MAX_PAIR_TYPES = 256;
    public static final int MAX_TARGET_PAIRS_PER_EPISODE = 16;
    public static final int MAX_EPISODE_LINES_PER_TICK = 128;
    public static final int EXTRA_FOCUS_EPISODE_LINES_PER_TICK = 32;
    public static final int MAX_SUMMARY_TYPES = 32;

    public record Target(String space, String plotId, int x, int y, int z, String blockId) {
        public Target {
            if(space==null||space.isBlank()||blockId==null||blockId.isBlank())
                throw new IllegalArgumentException("target must be verified");
        }
        public String type() { return space+"/"+blockId; }
        public String describe() {
            return "[space="+space+",plot="+(plotId==null?"NONE":plotId)
                    +",pos=("+x+","+y+","+z+"),block="+blockId+"]";
        }
    }
    public record Category(String left,String right) {
        public static Category of(Target a, Target b) {
            String x=a.type(),y=b.type();
            return x.compareTo(y)<=0?new Category(x,y):new Category(y,x);
        }
        public boolean isPhysicalPair(String a,String b) {
            return (left.equals("SABLE_PLOT/"+a) && right.equals("SABLE_PLOT/"+b))
                    || (left.equals("SABLE_PLOT/"+b) && right.equals("SABLE_PLOT/"+a));
        }
        @Override public String toString(){return left+" <-> "+right;}
    }
    private static final class Counts {
        long callbacks, episodes, approvedAssociated;
    }
    private static final class SeenPair {
        final Target a,b;
        final Map<ImpactMath.Reason,Long> reasons=new EnumMap<>(ImpactMath.Reason.class);
        String nativeGate;
        final String initialKinematics;
        long callbacks;
        SeenPair(Target a,Target b,String nativeGate,String initialKinematics) {
            this.a=a;this.b=b;this.nativeGate=nativeGate;
            this.initialKinematics=initialKinematics;
        }
        String describe() {
            return "{A="+a.describe()+",B="+b.describe()+",callbacks="+callbacks
                    +",candidateReasons="+reasons+",nativeBodyPairGate="+nativeGate
                    +",firstPreSolverEvidence="+initialKinematics+"}";
        }
    }
    private static final class Episode {
        final LinkedHashMap<String,SeenPair> pairs=new LinkedHashMap<>();
        final Map<Category,Boolean> types=new HashMap<>();
        long callbacks,unlistedPairs;
        long serverTick=Long.MIN_VALUE;
        String nativeRuntimeIds="UNKNOWN";
        boolean focusPhysical;
    }
    public record FinalEvent(ImpactEpisodeLedger.Key key,long serverTick,String nativeRuntimeIds,
                             long callbacks,int distinctPairs,long unlistedPairs,
                             boolean focusPhysical,ImpactMath.Result result,String pairs) {
        public boolean approved(){return result!=null&&result.approved();}
    }

    private final Map<ImpactEpisodeLedger.Key,Episode> pending=new HashMap<>();
    private final Map<Category,Counts> categories=new HashMap<>();
    private final Map<String,Long> focus=new HashMap<>();
    private long observedCallbacks,allEpisodes,allApproved,unlistedCategories;
    private long episodeLinesSuppressed,unlistedPairSamples;
    private long missingOther,unverifiedTargets;
    private final Map<String,Long> unverifiedReasons=new HashMap<>();
    private final Map<String,Long> unverifiedRawBlocks=new HashMap<>();

    /** Only call with independently verified live block identities. */
    public void observe(ImpactEpisodeLedger.Key key,Target a,Target b,
                        ImpactMath.Reason candidate,String nativeGate) {
        observe(key,Long.MIN_VALUE,"UNKNOWN",a,b,candidate,nativeGate,()->"NOT_SUPPLIED");
    }
    public void observe(ImpactEpisodeLedger.Key key,long sourceTick,String runtimeIds,Target a,Target b,
                        ImpactMath.Reason candidate,String nativeGate,Supplier<String> initialSample) {
        if(key==null||a==null||b==null||candidate==null||nativeGate==null||initialSample==null)
            throw new IllegalArgumentException("invalid trace");
        observedCallbacks++;
        Category category=Category.of(a,b);
        if(category.isPhysicalPair("minecraft:chain","minecraft:stone"))
            focus.merge("chainPlotStone",1L,Long::sum);
        if(category.isPhysicalPair("minecraft:reinforced_deepslate","minecraft:stone"))
            focus.merge("reinforcedPlotStone",1L,Long::sum);
        if(categories.containsKey(category)||categories.size()<MAX_PAIR_TYPES)
            categories.computeIfAbsent(category,k->new Counts()).callbacks++;
        else unlistedCategories++;
        Episode episode=pending.computeIfAbsent(key,k->new Episode());
        if(episode.callbacks==0) {
            episode.serverTick=sourceTick;
            episode.nativeRuntimeIds=runtimeIds==null?"UNKNOWN":runtimeIds;
        } else if(episode.serverTick!=sourceTick || !episode.nativeRuntimeIds.equals(runtimeIds)) {
            episode.nativeRuntimeIds="MIXED_RUNTIME_OR_TICK";
        }
        episode.callbacks++;
        if(category.isPhysicalPair("minecraft:chain","minecraft:stone")
                ||category.isPhysicalPair("minecraft:reinforced_deepslate","minecraft:stone"))
            episode.focusPhysical=true;
        if(categories.containsKey(category))episode.types.put(category,Boolean.TRUE);
        // Both orientations are semantically different callback records, but
        // one pair of verified target keys must not flood an episode's log.
        String pairKey=a.describe()+"->"+b.describe();
        SeenPair pair=episode.pairs.get(pairKey);
        if(pair==null) {
            if(episode.pairs.size()==MAX_TARGET_PAIRS_PER_EPISODE) {
                episode.unlistedPairs++;
                unlistedPairSamples++;
                return;
            }
            pair=new SeenPair(a,b,nativeGate,initialSample.get());
            episode.pairs.put(pairKey,pair);
        }
        pair.callbacks++;
        pair.reasons.merge(candidate,1L,Long::sum);
        if(!pair.nativeGate.equals(nativeGate))pair.nativeGate="MIXED_NATIVE_PAIR_STATUS";
    }
    public void unverified(boolean hasOther,String hitStatus,String otherStatus) {
        unverified(hasOther,hitStatus,otherStatus,"UNKNOWN_RAW_BLOCK");
    }
    public void unverified(boolean hasOther,String hitStatus,String otherStatus,String rawHitBlock) {
        if(!hasOther)missingOther++;
        else {
            unverifiedTargets++;
            unverifiedReasons.merge(hitStatus+"/"+otherStatus,1L,Long::sum);
        }
        if(rawHitBlock!=null)
            unverifiedRawBlocks.merge(rawHitBlock+"/"+(hasOther?"OTHER_UNVERIFIED":"NO_OTHER"),1L,Long::sum);
    }
    /** Called exactly once after cap and continuity gates, not at candidate approval. */
    public FinalEvent finish(ImpactEpisodeLedger.Key key, ImpactMath.Result finalDecision) {
        Episode episode=pending.remove(key);
        if(episode==null)throw new IllegalStateException("no observed episode: "+key);
        allEpisodes++;
        if(finalDecision!=null&&finalDecision.approved())allApproved++;
        for(Category category:episode.types.keySet()) {
            Counts stat=categories.get(category);
            if(stat!=null) {
                stat.episodes++;
                // An approved OWNER PAIR episode is not proof of per-block damage.
                if(finalDecision!=null&&finalDecision.approved())stat.approvedAssociated++;
            }
        }
        List<String> pairs=new ArrayList<>();
        episode.pairs.values().forEach(p->pairs.add(p.describe()));
        return new FinalEvent(key,episode.serverTick,episode.nativeRuntimeIds,episode.callbacks,
                episode.pairs.size(),episode.unlistedPairs,
                episode.focusPhysical,finalDecision,pairs.toString());
    }
    public void suppressEpisodeLine(){episodeLinesSuppressed++;}
    public long observedCallbacks(){return observedCallbacks;}
    public long allEpisodes(){return allEpisodes;}
    public long allApproved(){return allApproved;}
    public long episodeLinesSuppressed(){return episodeLinesSuppressed;}
    public long focusCount(String key){return focus.getOrDefault(key,0L);}
    public long missingOther(){return missingOther;}
    public long unverifiedTargets(){return unverifiedTargets;}
    public long unlistedCategories(){return unlistedCategories;}
    public long unlistedPairSamples(){return unlistedPairSamples;}
    public Map<String,Long> unverifiedReasons(){return Map.copyOf(unverifiedReasons);}
    public List<String> unverifiedHitBlockTypes(int max) {
        return unverifiedRawBlocks.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String,Long>>comparingLong(Map.Entry::getValue)
                        .reversed().thenComparing(Map.Entry::getKey))
                .limit(Math.max(0,max))
                .map(e->e.getKey()+"="+e.getValue()).toList();
    }
    public int pairTypeCount(){return categories.size();}
    public List<String> leadingTypes(int max) {
        return categories.entrySet().stream()
                .sorted(Comparator.<Map.Entry<Category,Counts>>comparingLong(e->e.getValue().callbacks)
                        .reversed().thenComparing(e->e.getKey().toString()))
                .limit(Math.max(0,max))
                .map(e->"{pair="+e.getKey()+",callbackPairs="+e.getValue().callbacks
                        +",ownerEpisodes="+e.getValue().episodes
                        +",approvedOwnerEpisodesAssociated="+e.getValue().approvedAssociated+"}")
                .toList();
    }
    public List<String> focusTypes() {
        return categories.entrySet().stream()
                .filter(e->e.getKey().left().contains("minecraft:chain")
                        || e.getKey().right().contains("minecraft:chain")
                        || e.getKey().left().contains("minecraft:reinforced_deepslate")
                        || e.getKey().right().contains("minecraft:reinforced_deepslate"))
                .sorted(Comparator.comparing(e->e.getKey().toString()))
                .map(e->"{pair="+e.getKey()+",callbackPairs="+e.getValue().callbacks
                        +",ownerEpisodes="+e.getValue().episodes
                        +",approvedOwnerEpisodesAssociated="+e.getValue().approvedAssociated+"}")
                .toList();
    }
    public void clear(){pending.clear();categories.clear();focus.clear();unverifiedReasons.clear();unverifiedRawBlocks.clear();}
}
