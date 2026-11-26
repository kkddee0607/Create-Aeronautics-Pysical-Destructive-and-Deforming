package com.physicaldestroydeform.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(targets="dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotDualPacket",remap=false)
public interface SableSnapshotTickAccessor {
    @Accessor("interpolationTick") int pdd$interpolationTick();
}
