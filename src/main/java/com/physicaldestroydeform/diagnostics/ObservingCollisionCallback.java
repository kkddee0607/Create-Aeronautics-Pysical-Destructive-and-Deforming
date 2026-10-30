package com.physicaldestroydeform.diagnostics;

import com.physicaldestroydeform.collision.SableFrameCapture;
import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;
import net.minecraft.core.BlockPos;
import org.joml.Vector3d;

/** Keep the delegate's call count, thrown exceptions, and exact return object unchanged. */
public final class ObservingCollisionCallback implements BlockSubLevelCollisionCallback {
    private static final double[] NEUTRAL_NATIVE_RESPONSE = {0, 0, 0, 0};
    private final BlockSubLevelCollisionCallback original;
    private final String blockId;
    private final String callbackType;

    public ObservingCollisionCallback(BlockSubLevelCollisionCallback original, String blockId) {
        this.original = original;
        this.blockId = blockId;
        this.callbackType = original == null ? "NONE" : original.getClass().getName();
    }

    @Override
    public double[] onCollision(int x, int y, int z, int otherX, int otherY, int otherZ,
                                double impactX, double impactY, double impactZ,
                                double signedVelocity, boolean hasOther) {
        double[] result = original == null ? NEUTRAL_NATIVE_RESPONSE
                : original.onCollision(x, y, z, otherX, otherY, otherZ,
                        impactX, impactY, impactZ, signedVelocity, hasOther);
        observe(RawObservation.SOURCE_NATIVE_CALLBACK, x, y, z, hasOther, otherX, otherY,
                otherZ, impactX, impactY, impactZ, signedVelocity,
                result != null && result.length > 3 && result[3] != 0);
        return result;
    }

    @Override
    public CollisionResult sable$onCollision(BlockPos hit, BlockPos other,
                                             Vector3d impactPosition, double signedVelocity) {
        CollisionResult result = original == null ? CollisionResult.NONE
                : original.sable$onCollision(hit, other, impactPosition, signedVelocity);
        if (hit != null && impactPosition != null) {
            observe(RawObservation.SOURCE_DIRECT_CALLBACK, hit.getX(), hit.getY(), hit.getZ(),
                    other != null, other == null ? 0 : other.getX(), other == null ? 0 : other.getY(),
                    other == null ? 0 : other.getZ(), impactPosition.x, impactPosition.y,
                    impactPosition.z, signedVelocity, result != null && result.removeCollision());
        }
        return result;
    }

    private void observe(String source, int x, int y, int z, boolean hasOther,
                         int otherX, int otherY, int otherZ, double impactX, double impactY,
                         double impactZ, double signedVelocity, boolean removed) {
        try {
            var frame = SableFrameCapture.observe(x, y, z, hasOther, otherX, otherY, otherZ,
                    impactX, impactY, impactZ, signedVelocity);
            var hit = frame.hit();
            String owner = hit==null ? RawObservation.OWNER_UNKNOWN : hit.space().name();
            // Callback frame is an unverified hypothesis. Block owner is audited later.
            String coordinateFrame = RawObservation.FRAME_UNVERIFIED;
            SableContactQueue.offer(new RawObservation(System.nanoTime(), source,
                    owner, coordinateFrame, blockId, callbackType, original != null,
                    x, y, z, hasOther, otherX, otherY, otherZ, impactX, impactY, impactZ,
                    signedVelocity, removed, frame));
        } catch (RuntimeException observationFailure) {
            SableContactQueue.captureError();
        }
    }
}
