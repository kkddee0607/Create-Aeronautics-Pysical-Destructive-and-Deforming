package com.physicaldestroydeform.mixin;

import com.physicaldestroydeform.collision.SableFrameCapture;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sable 2.0.6 exact call sites, verified via javap. Both hooks require matching
 * bytecode; no dependence on the NeoForge-only event class in sable-common Maven.
 * Pre executes after Sable's own prePhysicsTick event, before applyQueuedForces and solver.
 * Post executes after Sable's own postPhysicsTick event, after its pose updates.
 */
@Mixin(value = SubLevelPhysicsSystem.class, remap = false)
public abstract class SableStepSnapshotMixin {
    @Inject(method = "tickPipelinePhysics(Ldev/ryanhcode/sable/api/sublevel/ServerSubLevelContainer;)V",
            at = @At(value = "INVOKE", shift = At.Shift.AFTER,
                    target = "Ldev/ryanhcode/sable/platform/SableEventPublishPlatform;prePhysicsTick(Ldev/ryanhcode/sable/sublevel/system/SubLevelPhysicsSystem;D)V"),
            require = 1, remap = false)
    private void pdd$beforeSolver(ServerSubLevelContainer container, CallbackInfo ci) {
        SableFrameCapture.onPre((SubLevelPhysicsSystem)(Object)this);
    }

    @Inject(method = "tickPipelinePhysics(Ldev/ryanhcode/sable/api/sublevel/ServerSubLevelContainer;)V",
            at = @At(value = "INVOKE", shift = At.Shift.AFTER,
                    target = "Ldev/ryanhcode/sable/platform/SableEventPublishPlatform;postPhysicsTick(Ldev/ryanhcode/sable/sublevel/system/SubLevelPhysicsSystem;D)V"),
            require = 1, remap = false)
    private void pdd$afterSolver(ServerSubLevelContainer container, CallbackInfo ci) {
        SableFrameCapture.onPost((SubLevelPhysicsSystem)(Object)this);
    }
}
