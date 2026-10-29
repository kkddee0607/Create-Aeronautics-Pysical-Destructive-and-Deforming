package com.physicaldestroydeform.diagnostics;

import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;
import net.minecraft.core.BlockPos;
import org.joml.Vector3d;

/**
 * JNI-facing Sable callback decorator. Preserves the exact delegate return value
 * (including the array object from custom onCollision implementations).
 * Never changes velocity, movement, removal, or Sable physics state.
 */
public final class ObservingCollisionCallback implements BlockSubLevelCollisionCallback {
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
        // Call original ONCE, before telemetry. Custom overrides of onCollision are preserved.
        double[] response = original == null ? new double[]{0, 0, 0, 0}
                : original.onCollision(x, y, z, otherX, otherY, otherZ,
                        impactX, impactY, impactZ, signedVelocity, hasOther);
        observe(RawObservation.SOURCE_NATIVE_CALLBACK, x, y, z, hasOther,
                otherX, otherY, otherZ, impactX, impactY, impactZ, signedVelocity,
                response != null && response.length > 3 && response[3] != 0);
        return response;
    }

    @Override
    public CollisionResult sable$onCollision(BlockPos hit, BlockPos other,
                                             Vector3d impactPosition, double signedVelocity) {
        CollisionResult response = original == null ? CollisionResult.NONE
                : original.sable$onCollision(hit, other, impactPosition, signedVelocity);
        if (hit != null && impactPosition != null) {
            observe(RawObservation.SOURCE_DIRECT_CALLBACK, hit.getX(), hit.getY(), hit.getZ(),
                    other != null, other == null ? 0 : other.getX(),
                    other == null ? 0 : other.getY(), other == null ? 0 : other.getZ(),
                    impactPosition.x, impactPosition.y, impactPosition.z, signedVelocity,
                    response != null && response.removeCollision());
        }
        return response;
    }

    private void observe(String source, int x, int y, int z, boolean hasOther,
                         int otherX, int otherY, int otherZ,
                         double impactX, double impactY, double impactZ,
                         double signedVelocity, boolean removed) {
        // Recording MUST NOT cause a physics callback to fail or call its delegate again.
        try {
            SableContactQueue.offer(new RawObservation(System.nanoTime(), source,
                    RawObservation.OWNER_UNKNOWN, RawObservation.FRAME_UNVERIFIED,
                    blockId, callbackType, original != null, x, y, z, hasOther,
                    otherX, otherY, otherZ, impactX, impactY, impactZ,
                    signedVelocity, removed));
        } catch (RuntimeException failure) {
            SableContactQueue.captureError();
        }
    }
}
