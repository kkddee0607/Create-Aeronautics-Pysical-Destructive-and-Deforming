package com.physicaldestroydeform.diagnostics;

import java.util.Objects;

/**
 * Stable key for grouping observations within a single server tick/substep.
 *
 * <p>This only provides deterministic grouping infrastructure. It does not claim that two
 * native callback invocations with the same key are necessarily the same physical contact;
 * that policy requires runtime callback cadence evidence.</p>
 */
public record ObservationKey(
        long serverTick,
        int physicsSubstep,
        String levelKey,
        CollisionObservation.BlockCoordinate hitBlock,
        CollisionObservation.BlockCoordinate otherHitBlock
) {
    public ObservationKey {
        if (serverTick < 0) throw new IllegalArgumentException("serverTick must be >= 0");
        if (physicsSubstep < 0) throw new IllegalArgumentException("physicsSubstep must be >= 0");
        if (levelKey == null || levelKey.isBlank()) throw new IllegalArgumentException("levelKey must not be blank");
        Objects.requireNonNull(hitBlock, "hitBlock");
    }
}
