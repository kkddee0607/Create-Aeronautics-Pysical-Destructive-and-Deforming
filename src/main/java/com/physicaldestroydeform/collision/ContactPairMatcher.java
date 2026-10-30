package com.physicaldestroydeform.collision;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.physicaldestroydeform.collision.CollisionFrame.*;
import static com.physicaldestroydeform.collision.FrameMath.Vec;

/**
 * Bounded-tick, spatially indexed reciprocal callback matching. A round trip
 * through one pose does not count as evidence: both Sable callbacks are required.
 * No collision episode accounting or physical mutations happen here.
 */
public final class ContactPairMatcher {
    private ContactPairMatcher() {}
    public static final double TOLERANCE_BLOCKS = 0.25;

    public record Match(int first, int second, double separation,
                        Evidence verifiedFirst, Evidence verifiedSecond) {}
    public record Result(List<Match> matches, int unpaired, int invalid) {
        public Result { matches = List.copyOf(matches); }
    }
    private record PairKey(long epoch, long tick, String dimension, BlockTarget hit, BlockTarget other) {
        PairKey opposite() { return new PairKey(epoch, tick, dimension, other, hit); }
    }
    private record Bucket(PairKey pair, long x, long y, long z) {}

    /** O(n * 27) bucket probes, not an O(n^2) scan across a large contact batch. */
    public static Result match(List<Evidence> observations) {
        Map<Bucket, ArrayDeque<Integer>> pending = new HashMap<>();
        List<Match> matches = new ArrayList<>();
        int eligible = 0, invalid = 0;
        for (int i = 0; i < observations.size(); i++) {
            Evidence e = observations.get(i);
            if (e == null || e.quality() == Quality.INVALID) { invalid++; continue; }
            if (e.phase()!=Phase.PRE_SOLVER || e.other()==null || e.hit()==null
                    || e.contactWorldHypothesis()==null || !e.contactWorldHypothesis().finite())
                continue;
            eligible++;
            Vec p = e.contactWorldHypothesis();
            PairKey key = new PairKey(e.epoch(), e.serverTick(), e.dimension(), e.hit(), e.other());
            PairKey opposite = key.opposite();
            long cx = cell(p.x()), cy = cell(p.y()), cz = cell(p.z());
            int found = -1;
            Bucket foundBucket = null;
            double best = TOLERANCE_BLOCKS;
            // Probe the 3x3x3 neighborhood to handle contact points near a cell boundary.
            for (int dx=-1; dx<=1; dx++) for (int dy=-1; dy<=1; dy++) for (int dz=-1; dz<=1; dz++) {
                Bucket candidateBucket = new Bucket(opposite,cx+dx,cy+dy,cz+dz);
                ArrayDeque<Integer> candidates = pending.get(candidateBucket);
                if (candidates == null) continue;
                for (int earlier : candidates) {
                    Evidence other = observations.get(earlier);
                    if (!CollisionFrame.reciprocal(other, e)) continue;
                    double separation = CollisionFrame.contactSeparation(other,e);
                    if (separation <= best) {
                        best = separation;
                        found = earlier;
                        foundBucket = candidateBucket;
                    }
                }
            }
            if (found >= 0) {
                ArrayDeque<Integer> waiting = pending.get(foundBucket);
                waiting.removeFirstOccurrence(found);
                if (waiting.isEmpty()) pending.remove(foundBucket);
                matches.add(new Match(found, i, best,
                        observations.get(found).validated(), e.validated()));
            } else {
                pending.computeIfAbsent(new Bucket(key,cx,cy,cz), ignored -> new ArrayDeque<>()).addLast(i);
            }
        }
        return new Result(matches, eligible - matches.size()*2, invalid);
    }

    private static long cell(double value) { return (long)Math.floor(value / TOLERANCE_BLOCKS); }
}
