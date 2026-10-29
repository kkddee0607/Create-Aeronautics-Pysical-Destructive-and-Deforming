package com.physicaldestroydeform.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.physicaldestroydeform.diagnostics.ObservingCollisionCallback;
import com.physicaldestroydeform.diagnostics.SableContactQueue;
import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Wrap only the factory result, not the original callback call or its semantics. */
@Mixin(targets = "dev.ryanhcode.sable.physics.impl.rapier.collider.RapierVoxelColliderBakery", remap = false)
public abstract class SableUniversalCallbackMixin {
    @ModifyExpressionValue(
            method = "buildPhysicsDataForBlock(Lnet/minecraft/world/level/block/state/BlockState;)Ldev/ryanhcode/sable/physics/impl/rapier/collider/RapierVoxelColliderData;",
            at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/api/block/BlockWithSubLevelCollisionCallback;sable$getCallback(Lnet/minecraft/world/level/block/state/BlockState;)Ldev/ryanhcode/sable/api/physics/callback/BlockSubLevelCollisionCallback;"),
            remap = false, require = 1)
    private BlockSubLevelCollisionCallback pdd$wrapFactoryResult(BlockSubLevelCollisionCallback original, BlockState state) {
        // Air has no collision surface. Do not introduce a native callback for it.
        if (state.isAir()) return original;
        SableContactQueue.registered(original != null);
        return new ObservingCollisionCallback(original,
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    }
}
