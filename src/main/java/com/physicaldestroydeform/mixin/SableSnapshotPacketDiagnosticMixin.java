package com.physicaldestroydeform.mixin;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotDualPacket;
import dev.ryanhcode.sable.network.packets.PacketReceiveMode;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional diagnostic before Sable's original client snapshot handler (no packet modifications). */
@Mixin(targets="dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotDualPacket",remap=false)
public abstract class SableSnapshotPacketDiagnosticMixin {
    private static final Logger PDD_LOG=LoggerFactory.getLogger(SableSnapshotPacketDiagnosticMixin.class);
    private static final AtomicInteger PDD_MISSING_LOGGED=new AtomicInteger();
    @Shadow @Final private int interpolationTick;
    @Shadow @Final private List<ClientboundSableSnapshotDualPacket.Entry> entries;

    @Inject(method="handleClient(Lnet/minecraft/world/level/Level;Ldev/ryanhcode/sable/network/packets/PacketReceiveMode;)V",
            at=@At("HEAD"),remap=false,require=1)
    private void pdd$traceUnmappedPlot(Level level,PacketReceiveMode mode,CallbackInfo ci) {
        if(!Boolean.getBoolean("pdd.traceSublevelPackets") || PDD_MISSING_LOGGED.get()>=32)return;
        try {
            SubLevelContainer container=SubLevelContainer.getContainer(level);
            for(var entry:entries) {
                int x=ChunkPos.getX(entry.plotCoordinate());
                int z=ChunkPos.getZ(entry.plotCoordinate());
                if(container!=null && container.getSubLevel(x,z) instanceof ClientSubLevel)continue;
                int count=PDD_MISSING_LOGGED.getAndIncrement();
                if(count>=32)break;
                PDD_LOG.warn("[PDD-SUBLEVEL-NET] phase=SNAPSHOT_MISSING " +
                        "clientDimension={} interpolationTick={} mode={} plotChunkX={} plotChunkZ={} " +
                        "ownerUUID=UNAVAILABLE runtimeBodyId=UNAVAILABLE action=SABLE_HANDLER_UNCHANGED",
                        level.dimension().location(),interpolationTick,mode,x,z);
            }
        }catch(RuntimeException|LinkageError ignored) { /* never alter Sable packet handling */ }
    }
}
