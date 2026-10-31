package com.physicaldestroydeform.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Preserve Sable original array; tag the tick and dimension of its post-solver
 * native effects for conservative, independent Plot-Plot normal verification. */
@Mixin(targets = "dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline", remap = false)
public abstract class RapierCollisionTelemetryMixin {
    @Shadow @Final private ServerLevel level;
    @ModifyExpressionValue(
            method = "processCollisionEffects()V",
            at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/physics/impl/rapier/Rapier3D;clearCollisions(J)[D"),
            remap = false,
            require = 1)
    private double[] pdd$observeNativeCollisionArray(double[] original) {
        if (NativeCollisionTelemetry.ENABLED) NativeCollisionTelemetry.capture(original,level.dimension().location().toString(),level.getGameTime());
        return original;
    }
}
