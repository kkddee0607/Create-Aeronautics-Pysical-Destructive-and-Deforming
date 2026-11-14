package com.physicaldestroydeform.mixin;

import dev.ryanhcode.sable.sublevel.plot.heat.SubLevelHeatMapManager;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only view of Sable 2.0.6 HeatMap state. No split/physics hooks altered. */
@Mixin(value=SubLevelHeatMapManager.class,remap=false)
public interface SableHeatMapSafetyAccessor {
    @Accessor(value="initialized",remap=false) boolean pdd$initialized();
    @Accessor(value="splitComplete",remap=false) boolean pdd$splitComplete();
    @Accessor(value="floodfill",remap=false) ObjectList<BlockPos> pdd$floodfill();
    @Accessor(value="removed",remap=false) ObjectList<BlockPos> pdd$removed();
    @Accessor(value="newStarts",remap=false) ObjectList<BlockPos> pdd$newStarts();
    @Accessor(value="subLevelSplits",remap=false) Long2IntOpenHashMap pdd$subLevelSplits();
}
