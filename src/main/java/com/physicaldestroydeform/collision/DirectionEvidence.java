package com.physicaldestroydeform.collision;

/** Optional *verified* owner-local normal witness; absence denies deformation movement.
 * Merely observing an R2 point does not prove a signed collision orientation. */
public record DirectionEvidence(String ownerFrame, String normalSource,
                                String sourceQuality, long sourceTick, long sourceSubstep,
                                long transformEpoch, int signedX, int signedY, int signedZ) {
    public DirectionEvidence {
        if(ownerFrame==null||ownerFrame.isBlank() || normalSource==null||normalSource.isBlank()
                ||sourceQuality==null||sourceQuality.isBlank() || sourceTick<0||sourceSubstep<0
                ||transformEpoch<0 || Math.abs(signedX)>1||Math.abs(signedY)>1
                ||Math.abs(signedZ)>1||Math.abs(signedX)+Math.abs(signedY)+Math.abs(signedZ)!=1)
            throw new IllegalArgumentException("invalid verified direction witness");
    }
}
