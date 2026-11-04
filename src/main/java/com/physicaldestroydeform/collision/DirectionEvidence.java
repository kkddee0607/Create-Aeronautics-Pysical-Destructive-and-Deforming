package com.physicaldestroydeform.collision;

/** Optional owner-local normal witness; absence denies deformation movement.
 * sourceSubstep=-1 means post-effect cannot identify an individual solver substep.
 * Even a corroborated signed axis does NOT authorize a move before 0.7.x preflight. */
public record DirectionEvidence(String ownerFrame, String normalSource,
                                String sourceQuality, long sourceTick, long sourceSubstep,
                                long transformEpoch, int signedX, int signedY, int signedZ) {
    public DirectionEvidence {
        if(ownerFrame==null||ownerFrame.isBlank() || normalSource==null||normalSource.isBlank()
                ||sourceQuality==null||sourceQuality.isBlank() || sourceTick<0||sourceSubstep< -1
                ||transformEpoch<0 || Math.abs(signedX)>1||Math.abs(signedY)>1
                ||Math.abs(signedZ)>1||Math.abs(signedX)+Math.abs(signedY)+Math.abs(signedZ)!=1)
            throw new IllegalArgumentException("invalid verified direction witness");
    }
}
