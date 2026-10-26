package com.physicaldestroydeform.mixin;

import com.physicaldestroydeform.diagnostics.SableContactQueue;
import net.minecraft.core.BlockPos;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Observes the two concrete Sable 2.0.6 callback implementations after execution.
 * Fragile is the superclass for TNT/beehive/bell/abstract-bell response callbacks.
 * No interface default-method injections, callback replacement or classifier changes.
 */
@Mixin(targets = {
        "dev.ryanhcode.sable.physics.callback.FragileBlockCallback",
        "dev.ryanhcode.sable.neoforge.physics.callback.BeltBlockCallback"
}, remap = false)
public abstract class SableCollisionBridgeMixin {
    @Inject(method = "sable$onCollision(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lorg/joml/Vector3d;D)Ldev/ryanhcode/sable/api/physics/callback/BlockSubLevelCollisionCallback$CollisionResult;",
            at = @At("RETURN"), remap = false)
    private void pdd$afterSableCollision(BlockPos hit, BlockPos other,
                                         Vector3d impactPosition, double signedVelocity,
                                         CallbackInfoReturnable<?> callback) {
        if (!SableContactQueue.ENABLED) return;
        if (hit == null || impactPosition == null || callback.getReturnValue() == null) return;
        String resultText = callback.getReturnValue().toString();
        SableContactQueue.offer(new SableContactQueue.Contact(
                hit.getX(), hit.getY(), hit.getZ(),
                other == null ? 0 : other.getX(), other == null ? 0 : other.getY(), other == null ? 0 : other.getZ(),
                impactPosition.x, impactPosition.y, impactPosition.z, signedVelocity,
                other != null, this.getClass().getSimpleName(), resultText));
    }
}
