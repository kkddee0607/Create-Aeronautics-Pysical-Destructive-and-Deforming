package com.physicaldestroydeform.collision;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * PDD IDS 1.0.5-A (§22-23) bounded, read-only game-credit policy.
 *
 * <p>This is deliberately not a physics engine and force is not an impulse or
 * energy. A caller must prove owner identity, target identity and approaching/
 * separated geometry independently. The caller runs this on the server thread.
 * No Minecraft/Sable mutable object crosses this boundary.</p>
 */
public final class GameImpactCredit {
    public static final String POLICY_REVISION = "PDD-IDS-1.0.5-A/0.3.1";

    public record Settings(double forceEnter, double forceFloor, double forceGain,
                           double maxGroupQ, int separatedFrames, int maxEntries,
                           long maxIdleTicks, double risingRatio, int quietFrames) {
        /** Preserve existing callers and the 0.3.0 policy construction contract. */
        public Settings(double forceEnter,double forceFloor,double forceGain,
                        double maxGroupQ,int separatedFrames,int maxEntries,long maxIdleTicks) {
            this(forceEnter,forceFloor,forceGain,maxGroupQ,separatedFrames,
                    maxEntries,maxIdleTicks,1.8,2);
        }
        public Settings {
            if (!Double.isFinite(forceEnter) || forceEnter < 0 ||
                !Double.isFinite(forceFloor) || forceFloor < 0 ||
                !Double.isFinite(forceGain) || forceGain < 0 ||
                !Double.isFinite(maxGroupQ) || maxGroupQ < 0 ||
                separatedFrames < 2 || maxEntries < 1 || maxIdleTicks < 1 ||
                !Double.isFinite(risingRatio) || risingRatio < 1 || quietFrames < 2)
                throw new IllegalArgumentException("invalid provisional credit settings");
        }
        public static Settings provisional() { return new Settings(2, 2, 0.25, 64, 2, 4096, 600); }
        /** Per-version developer defaults, operator-overridable after gameplay testing. */
        public static Settings fromSystemProperties() {
            Settings d=provisional();
            return new Settings(
                numeric("forceEnter",d.forceEnter()),numeric("forceFloor",d.forceFloor()),
                numeric("forceGain",d.forceGain()),numeric("maxGroupQ",d.maxGroupQ()),
                Math.max(2,whole("separatedFrames",d.separatedFrames())),
                whole("maxEntries",d.maxEntries()),
                positiveTicks("maxIdleTicks",d.maxIdleTicks()),
                Math.max(1,numeric("risingRatio",d.risingRatio())),
                Math.max(2,whole("quietFrames",d.quietFrames())));
        }
        private static double numeric(String name,double fallback) {
            String text=System.getProperty("pdd.realfix."+name);
            if(text==null)return fallback;
            try {double value=Double.parseDouble(text);
                if(!Double.isFinite(value)||value<0) return fallback;
                return value;
            } catch(NumberFormatException malformed) {return fallback;}
        }
        private static int whole(String name,int fallback) {
            double value=numeric(name,fallback);
            return value>=1&&value<=Integer.MAX_VALUE&&value==Math.rint(value)?(int)value:fallback;
        }
        private static long positiveTicks(String name,long fallback) {
            double value=numeric(name,fallback);
            return value>=1 && value<=Long.MAX_VALUE && value==Math.rint(value)
                    ? (long)value : fallback;
        }
        public double candidate(double peak) {
            if (!Double.isFinite(peak) || peak < forceEnter) return 0;
            return Math.min(maxGroupQ, forceGain * Math.max(0, peak - forceFloor));
        }
    }

    /** Ordering uses stable owner identity plus owner generation, not runtime ID. */
    public record Owner(String stableKey, long generation) implements Comparable<Owner> {
        public Owner {
            if (stableKey == null || stableKey.isBlank() || generation < 0)
                throw new IllegalArgumentException("invalid owner");
        }
        @Override public int compareTo(Owner other) {
            int compare = stableKey.compareTo(other.stableKey);
            return compare != 0 ? compare : Long.compare(generation, other.generation);
        }
    }
    public record Pair(String runId, String dimension, Owner a, Owner b) {
        public Pair {
            if (runId == null || runId.isBlank() || dimension == null || dimension.isBlank()
                    || a == null || b == null || a.equals(b) || a.compareTo(b) >= 0)
                throw new IllegalArgumentException("pair requires canonical distinct owners");
        }
        public static Pair canonical(String runId, String dimension, Owner first, Owner second) {
            Objects.requireNonNull(first); Objects.requireNonNull(second);
            return first.compareTo(second) < 0 ? new Pair(runId, dimension, first, second)
                    : new Pair(runId, dimension, second, first);
        }
    }
    /** A target is verified by a separate owner-local BlockState and geometry audit. */
    public record Target(String ownerKey, String localBlock, String fingerprint) {
        public Target {
            if (ownerKey == null || ownerKey.isBlank() || localBlock == null || localBlock.isBlank()
                    || fingerprint == null || fingerprint.isBlank())
                throw new IllegalArgumentException("unverified target identity");
        }
    }
    public record ObservedForce(int bodyA, int bodyB, double force, int ordinal) {
        public ObservedForce {
            if (bodyA < -1 || bodyB < -1 || (bodyA == -1 && bodyB == -1)
                    || !Double.isFinite(force) || force < 0 || ordinal < 0)
                throw new IllegalArgumentException("invalid native force observation");
        }
    }
    public enum Phase { UNINITIALIZED, ARMED, ACTIVE, COOLING }
    public enum Reason {
        SEPARATION_WARMUP, INITIAL_CONTACT_UNKNOWN, NOT_ARMED,
        UNKNOWN_SEPARATION, APPROACH_UNVERIFIED, TARGET_UNVERIFIED,
        BELOW_THRESHOLD, APPROVED, DUPLICATE_EPISODE, STALE_FRAME,
        INVALID_FORCE, STATE_CAPACITY_EXCEEDED
    }
    public record Allocation(double sideA, double sideB, double unused) {
        public Allocation {
            if (!Double.isFinite(sideA) || !Double.isFinite(sideB) ||
                !Double.isFinite(unused) || sideA < 0 || sideB < 0 || unused < 0)
                throw new IllegalArgumentException("invalid allocation");
        }
        public double total() { return sideA+sideB+unused; }
    }
    public record Decision(Phase phase, Reason reason, double peakForce,
                           double issuedQ, Allocation allocation,
                           long episode, long episodeGeneration, long authorizationTick,
                           long tick, String policyRevision) {
        public boolean credited() { return reason == Reason.APPROVED && issuedQ > 0; }
    }
    /**
     * Already-grouped pair/tick input. A null target means unverified side;
     * caller MUST NOT manufacture a block target from body ID alone.
     */
    public record Frame(Pair pair, long tick, List<ObservedForce> records,
                        boolean positivelySeparated, boolean verifiedApproach,
                        Target targetA, Target targetB, double aggregatedPeak) {
        public Frame(Pair pair, long tick, List<ObservedForce> records,
                     boolean positivelySeparated, boolean verifiedApproach,
                     Target targetA, Target targetB) {
            this(pair,tick,records,positivelySeparated,verifiedApproach,targetA,targetB,
                    GameImpactCredit.peak(records));
        }
        public Frame {
            if (!Double.isFinite(aggregatedPeak) || aggregatedPeak < 0)
                throw new IllegalArgumentException("invalid native force peak");
            Objects.requireNonNull(records);
            if (records.isEmpty() && aggregatedPeak != 0)
                throw new IllegalArgumentException("peak without records");
            if (aggregatedPeak + 1e-12 < GameImpactCredit.peak(records))
                throw new IllegalArgumentException("inconsistent peak");
            Objects.requireNonNull(pair);
            records = List.copyOf(Objects.requireNonNull(records));
            if (tick < 0) throw new IllegalArgumentException("negative tick");
            if (targetA != null && !targetA.ownerKey().equals(pair.a().stableKey()))
                throw new IllegalArgumentException("wrong A target owner");
            if (targetB != null && !targetB.ownerKey().equals(pair.b().stableKey()))
                throw new IllegalArgumentException("wrong B target owner");
            if (positivelySeparated && !records.isEmpty())
                throw new IllegalArgumentException("contact and proven separation contradict");
        }
        public double forcePeak() { return aggregatedPeak; }
    }

    private static final class State {
        Phase phase=Phase.UNINITIALIZED;
        int separatedSamples;
        long tick=-1;
        long episode;
        long episodeGeneration;
        long authorizationTick=-1;
        boolean pendingApproach;
        int burstFrames;
    }
    private final Settings config;
    private final Map<Pair,State> states=new HashMap<>();
    public GameImpactCredit(Settings config) { this.config=Objects.requireNonNull(config); }
    public Settings settings() { return config; }
    public int activePairs() { return states.size(); }
    /** Read-only state snapshot for independently proven pre-contact clearance. */
    public Phase phase(Pair pair) {
        State state=states.get(Objects.requireNonNull(pair));
        return state==null?Phase.UNINITIALIZED:state.phase;
    }
    /** Episode generation increments on contact after rearm even when Q stays zero. */
    public long episodeGeneration(Pair pair) {
        State s=states.get(Objects.requireNonNull(pair));
        return s==null?0:s.episodeGeneration;
    }
    /** Immutable verification-only snapshot. Does not advance, rearm or consume evidence. */
    public record EvidenceSnapshot(Phase phase,long stateTick,int separatedSamples,
                                   boolean pendingApproach,int burstFrames,long episode,
                                   long episodeGeneration,long authorizationTick) {
        public boolean hasUnspentAuthorization() {
            return phase==Phase.ACTIVE && pendingApproach && burstFrames<2;
        }
    }
    public EvidenceSnapshot evidence(Pair pair) {
        State s=states.get(Objects.requireNonNull(pair));
        return s==null?new EvidenceSnapshot(Phase.UNINITIALIZED,-1,0,false,0,0,0,-1)
                :new EvidenceSnapshot(s.phase,s.tick,s.separatedSamples,s.pendingApproach,
                        s.burstFrames,s.episode,s.episodeGeneration,s.authorizationTick);
    }
    /** An unproved intervening frame breaks the consecutive-clearance streak. */
    public void invalidateSeparationStreak(Pair pair) {
        State s=states.get(Objects.requireNonNull(pair));
        if(s!=null && (s.phase==Phase.COOLING || s.phase==Phase.UNINITIALIZED))
            s.separatedSamples=0;
    }
    public void clear() { states.clear(); }
    /** Recycled native bodies or removed plots cannot retain the old episode. */
    public void invalidateOwner(String stableOwner) {
        Objects.requireNonNull(stableOwner);
        states.keySet().removeIf(p -> p.a().stableKey().equals(stableOwner)
                || p.b().stableKey().equals(stableOwner));
    }
    /** Optional verified surface re-approach gate, not a force-only shortcut. */
    public boolean risingEdge(double baseline,double current,int quietFrames,
                              boolean separatelyVerifiedRegionApproach) {
        return separatelyVerifiedRegionApproach && quietFrames>=config.quietFrames()
                && Double.isFinite(baseline) && baseline>0
                && Double.isFinite(current) && current>=config.forceEnter()
                && current>=baseline*config.risingRatio();
    }

    public Decision step(Frame frame) {
        Objects.requireNonNull(frame);
        State s=states.get(frame.pair());
        if (s == null) {
            if (states.size() >= config.maxEntries()) return decision(new State(),
                    Reason.STATE_CAPACITY_EXCEEDED,frame,0);
            s=new State(); states.put(frame.pair(),s);
        }
        if (frame.tick() <= s.tick) return decision(s,Reason.STALE_FRAME,frame,0);
        s.tick=frame.tick();
        if (frame.positivelySeparated()) {
            if (s.phase == Phase.ACTIVE) { s.phase=Phase.COOLING; s.separatedSamples=1; s.pendingApproach=false; }
            else {s.separatedSamples++; if(s.separatedSamples >= config.separatedFrames()) s.phase=Phase.ARMED; }
            return decision(s,Reason.SEPARATION_WARMUP,frame,0);
        }
        if (frame.records().isEmpty()) {
            // Silence is NEVER an assertion that two objects separated.
            // It also cannot extend a pending first-impact burst indefinitely.
            s.pendingApproach=false;
            return decision(s,Reason.UNKNOWN_SEPARATION,frame,0);
        }
        if (s.phase == Phase.COOLING || s.phase == Phase.UNINITIALIZED) {
            s.phase=Phase.ACTIVE; s.separatedSamples=0; s.pendingApproach=false;
            s.authorizationTick=-1;
            s.episodeGeneration++;
            return decision(s,Reason.INITIAL_CONTACT_UNKNOWN,frame,0);
        }
        if (s.phase == Phase.ARMED) {
            s.phase=Phase.ACTIVE; s.separatedSamples=0;
            s.pendingApproach=frame.verifiedApproach();
            s.authorizationTick=s.pendingApproach?frame.tick():-1;
            s.episodeGeneration++;
            s.burstFrames=0;
        } else if (s.phase != Phase.ACTIVE || !s.pendingApproach || s.burstFrames >= 2) {
            return decision(s,Reason.DUPLICATE_EPISODE,frame,0);
        }
        s.burstFrames++;
        if (!s.pendingApproach) return decision(s,Reason.APPROACH_UNVERIFIED,frame,0);
        if (frame.targetA() == null && frame.targetB() == null)
            return decision(s,Reason.TARGET_UNVERIFIED,frame,0);
        final double q=config.candidate(frame.forcePeak());
        if (q <= 0) return decision(s,Reason.BELOW_THRESHOLD,frame,0);
        s.pendingApproach=false; // one-credit, including the burst's following frames
        s.episode++;
        return decision(s,Reason.APPROVED,frame,q);
    }

    public void prune(long tick) {
        states.entrySet().removeIf(e -> tick > e.getValue().tick &&
                tick - e.getValue().tick > config.maxIdleTicks());
    }
    private static Decision decision(State s,Reason reason,Frame frame,double q) {
        final double a=frame.targetA()==null?0:q*0.5;
        final double b=frame.targetB()==null?0:q*0.5;
        // Two targets resolving to exactly the same owned block do not double credit it.
        final double bShare=frame.targetA()!=null && frame.targetA().equals(frame.targetB())?0:b;
        return new Decision(s.phase,reason,frame.forcePeak(),q,
                new Allocation(a,bShare,q-a-bShare),s.episode,s.episodeGeneration,
                s.authorizationTick,frame.tick(),POLICY_REVISION);
    }

    /**
     * Parse Sable's documented 15-double post-effect records.
     * Invalid record returns an empty batch (not a fabricated zero-ID owner).
     */
    public static List<ObservedForce> parse(double[] nativeArray) {
        if (nativeArray==null || nativeArray.length%15!=0) return List.of();
        List<ObservedForce> values=new ArrayList<>(nativeArray.length/15);
        for(int offset=0;offset<nativeArray.length;offset+=15) {
            boolean valid=true;
            for(int i=0;i<15;i++)valid&=Double.isFinite(nativeArray[offset+i]);
            double a=nativeArray[offset], b=nativeArray[offset+1], f=nativeArray[offset+2];
            valid &= a==Math.rint(a) && b==Math.rint(b) && a>=-1 && b>=-1
                    && a<=Integer.MAX_VALUE && b<=Integer.MAX_VALUE && f>=0
                    && !(a==-1 && b==-1);
            if (valid) values.add(new ObservedForce((int)a,(int)b,f,offset/15));
        }
        return List.copyOf(values);
    }

    /** Native force is the pair peak, NEVER a sum across points/manifolds. */
    public static double peak(List<ObservedForce> records) {
        return records.stream().mapToDouble(ObservedForce::force).max().orElse(0);
    }
}
