package com.physicaldestroydeform.diagnostics;

/**
 * An unnormalized, immutable Sable callback sample. Coordinates, velocity and
 * owner are NOT asserted to use world space, m/s, or a particular physics step.
 * Kept as the input type for the 0.2.13 normalizer; it does not authorize damage.
 */
public record RawObservation(
        long capturedNanos, String source, String ownerSpace, String coordinateFrame,
        String blockId, String callbackType, boolean hadOriginal,
        int hitX, int hitY, int hitZ, boolean hasOther,
        int otherX, int otherY, int otherZ,
        double impactX, double impactY, double impactZ, double signedVelocity,
        boolean removeCollision) {
    public static final String OWNER_UNKNOWN = "UNKNOWN";
    public static final String FRAME_UNVERIFIED = "UNVERIFIED";
    public static final String SOURCE_NATIVE_CALLBACK = "SABLE_BAKERY_NATIVE_CALLBACK";
    public static final String SOURCE_DIRECT_CALLBACK = "SABLE_BAKERY_DIRECT_CALLBACK";
}
