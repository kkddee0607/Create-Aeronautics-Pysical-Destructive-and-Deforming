package com.physicaldestroydeform.diagnostics;

/** Unnormalized callback observation. No assumption about frame, body ownership, or speed units. */
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
