package com.physicaldestroydeform.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Sable 2.0.6: observe the exact native contact array consumed by
 * RapierPhysicsPipeline.processCollisionEffects(), including collider pairs
 * whose block does NOT implement BlockSubLevelCollisionCallback.
 * Returning the same array preserves the original Sable collision handling.
 */
@Mixin(targets = "dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline", remap = false)
public abstract class RapierCollisionTelemetryMixin {
    @ModifyExpressionValue(
            method = "processCollisionEffects()V",
            at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/physics/impl/rapier/Rapier3D;clearCollisions(J)[D"),
            remap = false,
            require = 1)
    private double[] pdd$observeNativeCollisionArray(double[] original) {
        if (NativeCollisionTelemetry.ENABLED) NativeCollisionTelemetry.capture(original);
        return original;
    }
}
