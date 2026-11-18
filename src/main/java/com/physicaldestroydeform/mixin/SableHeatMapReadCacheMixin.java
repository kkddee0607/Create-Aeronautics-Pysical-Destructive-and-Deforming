package com.physicaldestroydeform.mixin;

import com.physicaldestroydeform.plot.PlotHeatMapReadCache;
import dev.ryanhcode.sable.sublevel.plot.heat.SubLevelHeatMapManager;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sable 2.0.6: observe (do not replace, cancel, or reorder) every public
 * native mutator of the safety fields. Private step/split/rebuild execute
 * under tick(). An exceptional return leaves the cache disabled rather
 * than accepting a stale sample. */
@Mixin(value=SubLevelHeatMapManager.class,remap=false)
public abstract class SableHeatMapReadCacheMixin implements PlotHeatMapReadCache.Witness {
    @Unique private final PlotHeatMapReadCache pdd$readCache=new PlotHeatMapReadCache();

    @Inject(method="tick()V",at=@At("HEAD"),require=1,remap=false)
    private void pdd$beforeNativeTick(CallbackInfo ci){pdd$readCache.beginMutation();}

    @Inject(method="tick()V",at=@At("RETURN"),require=1,remap=false)
    private void pdd$afterNativeTick(CallbackInfo ci){pdd$readCache.endMutation();}

    @Inject(method={"onSolidAdded(Lnet/minecraft/core/BlockPos;)V","onSolidRemoved(Lnet/minecraft/core/BlockPos;)V"},
            at=@At("HEAD"),require=2,remap=false)
    private void pdd$beforeSolidChange(BlockPos pos,CallbackInfo ci){pdd$readCache.beginMutation();}

    @Inject(method={"onSolidAdded(Lnet/minecraft/core/BlockPos;)V","onSolidRemoved(Lnet/minecraft/core/BlockPos;)V"},
            at=@At("RETURN"),require=2,remap=false)
    private void pdd$afterSolidChange(BlockPos pos,CallbackInfo ci){pdd$readCache.endMutation();}

    @Override public PlotHeatMapReadCache pdd$heatMapReadCache(){return pdd$readCache;}
}
