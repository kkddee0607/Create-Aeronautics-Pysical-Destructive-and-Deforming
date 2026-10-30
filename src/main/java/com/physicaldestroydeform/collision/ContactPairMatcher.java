package com.physicaldestroydeform.collision;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static com.physicaldestroydeform.collision.CollisionFrame.*;

/**
 * Pairs only UNAMBIGUOUS reciprocal owner keys in one physics step. There is no
 * invented manifold ID: multiple contacts for the same owner pair remain
 * PAIR_AMBIGUOUS. World-point proximity is never a target-identity gate.
 */
public final class ContactPairMatcher {
    private ContactPairMatcher() {}
    /** Legacy diagnostic threshold, NOT a Sable precision or ownership contract. */
    public static final double TOLERANCE_BLOCKS = 0.25;
    public record Match(int first, int second, double separation,
                        Evidence firstEvidence, Evidence secondEvidence) {}
    public record Result(List<Match> matches, int unpaired, int invalid, int ambiguous) {
        public Result { matches = List.copyOf(matches); }
    }
    private record PairKey(long epoch, long tick, String dimension, BlockTarget hit, BlockTarget other) {
        PairKey opposite() { return new PairKey(epoch, tick, dimension, other, hit); }
    }
    public static Result match(List<Evidence> observations) {
        Map<PairKey, List<Integer>> groups = new HashMap<>();
        int invalid=0, ambiguous=0, unpaired=0;
        for(int i=0;i<observations.size();i++) {
            Evidence e=observations.get(i);
            if(e==null || e.phase()!=Phase.PRE_SOLVER || e.hit()==null || !e.hit().valid()
                    || e.other()==null || !e.other().valid() || e.hit().equals(e.other())) {
                invalid++;
                continue;
            }
            groups.computeIfAbsent(new PairKey(e.epoch(),e.serverTick(),e.dimension(),e.hit(),e.other()),
                    ignored->new ArrayList<>()).add(i);
        }
        List<Match> matches=new ArrayList<>();
        java.util.Set<PairKey> processed=new java.util.HashSet<>();
        for (var entry:groups.entrySet()) {
            PairKey key=entry.getKey();
            if(!processed.add(key)) continue;
            PairKey opposite=key.opposite();
            processed.add(opposite);
            var first=entry.getValue();
            var second=groups.get(opposite);
            if(second==null) {unpaired+=first.size(); continue;}
            if(first.size()!=1 || second.size()!=1) {
                // Do not pair by callback order, location tolerance or placeholder position.
                ambiguous+=first.size()+second.size();
                continue;
            }
            // HashMap traversal can visit either directional key first. Expose
            // callback indices in input order so callers get stable evidence
            // orientation independent of Plot UUID/hash or callback interleaving.
            int a=Math.min(first.get(0),second.get(0));
            int b=Math.max(first.get(0),second.get(0));
            Evidence ea=observations.get(a),eb=observations.get(b);
            double separation=Double.NaN;
            if(ea.contactWorldHypothesis()!=null && eb.contactWorldHypothesis()!=null
                    && ea.contactWorldHypothesis().finite() && eb.contactWorldHypothesis().finite())
                separation=CollisionFrame.contactSeparation(ea,eb);
            matches.add(new Match(a,b,separation,ea,eb));
        }
        // Hash iteration also gives nondeterministic order across separate pairs.
        matches.sort(Comparator.comparingInt(Match::first));
        return new Result(matches,unpaired,invalid,ambiguous);
    }
}
