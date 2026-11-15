package com.physicaldestroydeform.mixin;

import com.physicaldestroydeform.plot.PlotHeatMapTickWitness;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sable 2.0.6: observe successful return of the actual HeatMap.tick() call.
 * Never mark a skipped tick, an exception, or a synthetic physics step complete.
 */
@Mixin(value=ServerSubLevel.class,remap=false)
public abstract class SablePlotTickWitnessMixin implements PlotHeatMapTickWitness {
    @Unique private long pdd$heatMapTick=-1;

    @Inject(method="tick()V",at=@At(value="INVOKE",shift=At.Shift.AFTER,
            target="Ldev/ryanhcode/sable/sublevel/plot/heat/SubLevelHeatMapManager;tick()V"),
            require=1,remap=false)
    private void pdd$afterHeatMapTick(CallbackInfo ci) {
        var server=((ServerSubLevel)(Object)this).getLevel().getServer();
        if(server!=null && server.isSameThread()) pdd$heatMapTick=server.getTickCount();
    }

    @Override public long pdd$completedHeatMapServerTick() { return pdd$heatMapTick; }
}
