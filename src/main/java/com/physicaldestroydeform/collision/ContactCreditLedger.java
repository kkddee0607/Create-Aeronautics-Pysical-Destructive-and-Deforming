package com.physicaldestroydeform.collision;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * IDS 1.0.6 §22.4: one read-only debit ledger for a verified pair episode.
 * Native contact point ordinals are never solver contact IDs or credit IDs.
 * A single source force event may reach several independently audited blocks,
 * but its Q is issued once, then split among bounded verified targets per side.
 */
public final class ContactCreditLedger {
    public static final String ALGORITHM_REVISION = "PDD-IDS-1.0.6/0.4.0scaleFix2";
    // Never allocate one side to more than 32 independently verified owner-local targets.
    public static final int MAX_TARGETS_PER_SIDE = 32;
    public static final int MAX_REGIONS = 2 * MAX_TARGETS_PER_SIDE;
    public static final int MAX_ENTRIES = 4096;
    public static final long IDLE_TICKS = 600;

    /** Only sources with a demonstrated common cause may share a CreditId. */
    public enum Source { SOLVER_VERIFIED, SABLE_POST_EFFECT, SPECIAL_CALLBACK_HINT }
    public enum Status { ISSUED, DUPLICATE_CAUSE, INVALID_TARGET_SET, LEDGER_CAPACITY, INSUFFICIENT_EVIDENCE, HINT_NOT_AUTHORITATIVE }
    public record CreditId(GameImpactCredit.Pair pair, long episodeGeneration) {
        public CreditId {
            Objects.requireNonNull(pair);
            if (episodeGeneration <= 0) throw new IllegalArgumentException("invalid episode generation");
        }
    }
    /** One verified source-region observation. Null on one side means unverified. */
    public record Region(GameImpactCredit.Target a, GameImpactCredit.Target b) {
        public Region {
            if (a == null && b == null) throw new IllegalArgumentException("empty contact region");
        }
    }
    public record Share(GameImpactCredit.Target target, double q) {
        public Share {
            Objects.requireNonNull(target);
            if (!Double.isFinite(q) || q < 0) throw new IllegalArgumentException("invalid debit");
        }
    }
    public record Grant(CreditId id, Source source, Status status, long tick,
                        double issuedQ, List<Share> a, List<Share> b,
                        double unused, int observedRegions, String algorithmRevision) {
        public Grant {
            a = List.copyOf(a);
            b = List.copyOf(b);
        }
        public double allocatedA() {return a.stream().mapToDouble(Share::q).sum();}
        public double allocatedB() {return b.stream().mapToDouble(Share::q).sum();}
        public double accounted() {return allocatedA()+allocatedB()+unused;}
        /** All issued Q is sealed into reserved shares or unused on issue. */
        public double availableQ() {return 0;}
        /** 0.4.0 material plans are predictions, not committed world damage. */
        public double spent() {return 0;}
        public boolean credited() {return status==Status.ISSUED && issuedQ>0;}
    }
    private record Stored(Source source, long tick, Grant grant) {}
    private final Map<CreditId, Stored> recorded = new HashMap<>();
    public int size() {return recorded.size();}
    public Grant lookup(CreditId id) {
        var item=recorded.get(Objects.requireNonNull(id));
        return item==null?null:item.grant();
    }
    public void clear() {recorded.clear();}
    public void invalidateOwner(String owner) {
        recorded.keySet().removeIf(k -> k.pair().a().stableKey().equals(owner)
                || k.pair().b().stableKey().equals(owner));
    }
    /** Unobserved events never mint debt; stale history is only garbage-collected. */
    public void prune(long now) {
        recorded.entrySet().removeIf(e -> now > e.getValue().tick
                && now - e.getValue().tick > IDLE_TICKS);
    }

    /** The caller must first pass GameImpactCredit's ARMED/new-approach gate. */
    public Grant issue(GameImpactCredit.Pair pair, GameImpactCredit.Decision decision,
                       Source source, List<Region> evidence) {
        Objects.requireNonNull(pair); Objects.requireNonNull(decision);
        Objects.requireNonNull(source); Objects.requireNonNull(evidence);
        CreditId id=new CreditId(pair,Math.max(1,decision.episodeGeneration()));
        if (!decision.credited())
            return empty(id,source,Status.INSUFFICIENT_EVIDENCE,decision.tick(),evidence.size());
        if (recorded.containsKey(id))
            return empty(id,source,Status.DUPLICATE_CAUSE,decision.tick(),evidence.size());
        if (source==Source.SPECIAL_CALLBACK_HINT)
            return empty(id,source,Status.HINT_NOT_AUTHORITATIVE,decision.tick(),evidence.size());
        if (recorded.size()>=MAX_ENTRIES)
            return empty(id,source,Status.LEDGER_CAPACITY,decision.tick(),evidence.size());

        // Linked source contacts may report the same target repeatedly. They
        // only increase observation count, never target Q or source Q.
        if (evidence.isEmpty() || evidence.size()>MAX_REGIONS)
            return empty(id,source,Status.INVALID_TARGET_SET,decision.tick(),evidence.size());
        Set<GameImpactCredit.Target> a=new HashSet<>(), b=new HashSet<>();
        Map<String,String> aCells=new HashMap<>(), bCells=new HashMap<>();
        for(Region region:evidence) {
            if (region==null) return empty(id,source,Status.INVALID_TARGET_SET,decision.tick(),evidence.size());
            if(region.a()!=null) {
                if(!region.a().ownerKey().equals(pair.a().stableKey()))
                    return empty(id,source,Status.INVALID_TARGET_SET,decision.tick(),evidence.size());
                String earlier=aCells.putIfAbsent(region.a().localBlock(),region.a().fingerprint());
                if(earlier!=null && !earlier.equals(region.a().fingerprint()))
                    return empty(id,source,Status.INVALID_TARGET_SET,decision.tick(),evidence.size());
                a.add(region.a());
            }
            if(region.b()!=null) {
                if(!region.b().ownerKey().equals(pair.b().stableKey()))
                    return empty(id,source,Status.INVALID_TARGET_SET,decision.tick(),evidence.size());
                String earlier=bCells.putIfAbsent(region.b().localBlock(),region.b().fingerprint());
                if(earlier!=null && !earlier.equals(region.b().fingerprint()))
                    return empty(id,source,Status.INVALID_TARGET_SET,decision.tick(),evidence.size());
                b.add(region.b());
            }
        }
        if(a.size()>MAX_TARGETS_PER_SIDE || b.size()>MAX_TARGETS_PER_SIDE || a.isEmpty()&&b.isEmpty())
            return empty(id,source,Status.INVALID_TARGET_SET,decision.tick(),evidence.size());

        var partsA=split(a,decision.allocation().sideA());
        var partsB=split(b,decision.allocation().sideB());
        double sum=partsA.stream().mapToDouble(Share::q).sum()
                +partsB.stream().mapToDouble(Share::q).sum();
        double unused=decision.issuedQ()-sum;
        if (!Double.isFinite(unused) || unused < -1e-8 || sum > decision.issuedQ()+1e-8)
            return empty(id,source,Status.INVALID_TARGET_SET,decision.tick(),evidence.size());
        // No block/world commit before v0.4.1/0.5.1. Share means reserved Q.
        Grant issued=new Grant(id,source,Status.ISSUED,decision.tick(),decision.issuedQ(),
                partsA,partsB,Math.max(0,unused),evidence.size(),ALGORITHM_REVISION);
        recorded.put(id,new Stored(source,decision.tick(),issued));
        return issued;
    }
    private static List<Share> split(Set<GameImpactCredit.Target> targets,double total) {
        if(targets.isEmpty() || total==0)return List.of();
        var ordered=new ArrayList<>(targets);
        ordered.sort(Comparator.comparing(GameImpactCredit.Target::ownerKey)
                .thenComparing(GameImpactCredit.Target::localBlock)
                .thenComparing(GameImpactCredit.Target::fingerprint));
        var out=new ArrayList<Share>(ordered.size());
        double prior=0;
        for(int i=0;i<ordered.size();i++) {
            double amount=i==ordered.size()-1?total-prior:total/ordered.size();
            out.add(new Share(ordered.get(i),amount));
            prior+=amount;
        }
        return List.copyOf(out);
    }
    private static Grant empty(CreditId id,Source source,Status status,long tick,int regions) {
        return new Grant(id,source,status,tick,0,List.of(),List.of(),0,regions,ALGORITHM_REVISION);
    }
}
