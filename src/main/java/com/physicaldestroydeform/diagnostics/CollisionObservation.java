package com.physicaldestroydeform.diagnostics;

import java.util.Objects;

/**
 * API-independent record of a collision callback observation.
 *
 * <p>This type is deliberately not wired to Sable's native callback. Its coordinates retain
 * an explicit frame label because Sable's impact position may be sublevel-plot or global
 * coordinates depending on which sublevel contains the hit block. The velocity remains signed;
 * it is not presumed to be energy or a calibrated damage measure.</p>
 */
public record CollisionObservation(
        long serverTick,
        int physicsSubstep,
        String callbackKind,
        String coordinateFrame,
        BlockCoordinate hitBlock,
        BlockCoordinate otherHitBlock,
        double impactX,
        double impactY,
        double impactZ,
        double impactVelocity,
        boolean hasOtherBlock
) {
    public CollisionObservation {
        if (serverTick < 0) throw new IllegalArgumentException("serverTick must be >= 0");
        if (physicsSubstep < 0) throw new IllegalArgumentException("physicsSubstep must be >= 0");
        callbackKind = requireText("callbackKind", callbackKind);
        coordinateFrame = requireText("coordinateFrame", coordinateFrame);
        Objects.requireNonNull(hitBlock, "hitBlock");
        if (hasOtherBlock != (otherHitBlock != null)) {
            throw new IllegalArgumentException("otherHitBlock must be present exactly when hasOtherBlock is true");
        }
        requireFinite("impactX", impactX);
        requireFinite("impactY", impactY);
        requireFinite("impactZ", impactZ);
        requireFinite("impactVelocity", impactVelocity);
    }

    public boolean hasUsableVelocityMagnitude() {
        return Math.abs(impactVelocity) > 0.0;
    }

    private static String requireText(String name, String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static void requireFinite(String name, double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    }

    public record BlockCoordinate(int x, int y, int z) {}
}
