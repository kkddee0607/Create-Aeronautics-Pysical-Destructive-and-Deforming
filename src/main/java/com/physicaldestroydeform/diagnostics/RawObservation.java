package com.physicaldestroydeform.diagnostics;

import com.physicaldestroydeform.collision.CollisionFrame;

/** Unnormalized callback observation. No assumption about frame, body ownership, or speed units. */
public record RawObservation(
        long capturedNanos, String source, String ownerSpace, String coordinateFrame,
        String blockId, String callbackType, boolean hadOriginal,
        int hitX, int hitY, int hitZ, boolean hasOther,
        int otherX, int otherY, int otherZ,
        double impactX, double impactY, double impactZ, double signedVelocity,
        boolean removeCollision, CollisionFrame.Evidence frameEvidence) {
    /** Compatibility for 0.2.12 callback-contract tests and synthetic data. */
    public RawObservation(long capturedNanos, String source, String ownerSpace, String coordinateFrame,
            String blockId, String callbackType, boolean hadOriginal,
            int hitX, int hitY, int hitZ, boolean hasOther,
            int otherX, int otherY, int otherZ, double impactX, double impactY,
            double impactZ, double signedVelocity, boolean removeCollision) {
        this(capturedNanos, source, ownerSpace, coordinateFrame, blockId, callbackType,
                hadOriginal, hitX, hitY, hitZ, hasOther, otherX, otherY, otherZ,
                impactX, impactY, impactZ, signedVelocity, removeCollision,
                CollisionFrame.Evidence.invalid("LEGACY_OR_SYNTHETIC_OBSERVATION"));
    }
    public static final String OWNER_UNKNOWN = "UNKNOWN";
    public static final String FRAME_UNVERIFIED = "UNVERIFIED";
    public static final String SOURCE_NATIVE_CALLBACK = "SABLE_BAKERY_NATIVE_CALLBACK";
    public static final String SOURCE_DIRECT_CALLBACK = "SABLE_BAKERY_DIRECT_CALLBACK";
}
