package com.physicaldestroydeform.collision;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Pure owner-local evidence for a fresh contacted voxel within one Plot/World
 * pair. A force peak or solver contact ordinal is never a region identity. */
public final class RegionalContactProof {
    private RegionalContactProof() {}

    public record Snapshot(Set<String> a, Set<String> b) {
        public Snapshot {
            a = Set.copyOf(a);
            b = Set.copyOf(b);
        }
    }

    public static Snapshot capture(List<ContactCreditLedger.Region> regions) {
        Objects.requireNonNull(regions);
        Set<String> a = new HashSet<>(), b = new HashSet<>();
        for (var r : regions) {
            if (r.a() != null) a.add(r.a().localBlock());
            if (r.b() != null) b.add(r.b().localBlock());
        }
        return new Snapshot(a, b);
    }

    /** A truly new, shape-audited owner cell is necessary but NOT sufficient
     * for credit: the caller must additionally prove new pre-solver approach. */
    public static boolean hasNewCells(Snapshot previous, List<ContactCreditLedger.Region> now) {
        if (previous == null) return false;
        for (var r : now) {
            if (r.a() != null && !previous.a().contains(r.a().localBlock())) return true;
            if (r.b() != null && !previous.b().contains(r.b().localBlock())) return true;
        }
        return false;
    }
}
