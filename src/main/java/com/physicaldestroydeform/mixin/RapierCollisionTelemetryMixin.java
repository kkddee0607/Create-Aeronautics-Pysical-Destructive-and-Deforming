package com.physicaldestroydeform.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import net.minecraft.server.level.ServerLevel;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import com.physicaldestroydeform.collision.FrameMath;
import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry.OwnerFrame;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Preserve Sable original array; tag the tick and dimension of its post-solver
 * native effects for conservative, independent Plot-Plot normal verification. */
@Mixin(targets = "dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline", remap = false)
public abstract class RapierCollisionTelemetryMixin {
    @Shadow @Final private ServerLevel level;
    // Actual Sable 2.0.6 Rapier JAR field: map from native runtime IDs to owners.
    @Shadow @Final private Int2ObjectMap<ServerSubLevel> activeSubLevels;

    private OwnerFrame pdd$nativeOwner(int runtimeId) {
        ServerSubLevel sub=activeSubLevels.get(runtimeId);
        if(sub==null || sub.isRemoved() || sub.getUniqueId()==null)return null;
        var p=sub.logicalPose();
        var t=p.position();var c=p.rotationPoint();var sc=p.scale();var q=p.orientation();
        var pose=new FrameMath.Pose(new FrameMath.Vec(t.x(),t.y(),t.z()),
                new FrameMath.Vec(c.x(),c.y(),c.z()),
                new FrameMath.Vec(sc.x(),sc.y(),sc.z()),q.x(),q.y(),q.z(),q.w());
        return new OwnerFrame(sub.getUniqueId(),pose);
    }
    @ModifyExpressionValue(
            method = "processCollisionEffects()V",
            at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/physics/impl/rapier/Rapier3D;clearCollisions(J)[D"),
            remap = false,
            require = 1)
    private double[] pdd$observeNativeCollisionArray(double[] original) {
        if (NativeCollisionTelemetry.ENABLED) {
            try {
                NativeCollisionTelemetry.capture(original,level.dimension().location().toString(),
                        level.getGameTime(),this::pdd$nativeOwner);
            } catch(RuntimeException observerError) {
                // Observers must not change Sable's clearCollisions array/exception path.
                NativeCollisionTelemetry.observerError();
            }
        }
        return original;
    }
}
