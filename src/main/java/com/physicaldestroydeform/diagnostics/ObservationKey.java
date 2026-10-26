package com.physicaldestroydeform.diagnostics;

import java.util.Objects;

/** Logical grouping key, not evidence of identical physical contact. */
public record ObservationKey(long serverTick, int physicsSubstep, String levelKey,
                             CollisionObservation.BlockCoordinate hitBlock,
                             CollisionObservation.BlockCoordinate otherHitBlock) {
    public ObservationKey {
        if (serverTick < 0) throw new IllegalArgumentException("serverTick must be >= 0");
        if (physicsSubstep < 0) throw new IllegalArgumentException("physicsSubstep must be >= 0");
        if (levelKey == null || levelKey.isBlank()) throw new IllegalArgumentException("levelKey must not be blank");
        Objects.requireNonNull(hitBlock, "hitBlock");
    }
}
