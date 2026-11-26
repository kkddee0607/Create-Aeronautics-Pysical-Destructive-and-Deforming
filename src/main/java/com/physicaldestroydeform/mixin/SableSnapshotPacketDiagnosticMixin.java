package com.physicaldestroydeform.mixin;

import com.physicaldestroydeform.diagnostics.RogTrace;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotDualPacket;
import dev.ryanhcode.sable.network.packets.PacketReceiveMode;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import java.util.List;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Read-only pre-handler observation. Counters continue after the detail cap. */
@Mixin(targets="dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotDualPacket",remap=false)
public abstract class SableSnapshotPacketDiagnosticMixin {
    @Shadow @Final private int interpolationTick;
    @Shadow @Final private List<ClientboundSableSnapshotDualPacket.Entry> entries;

    @Inject(method="handleClient(Lnet/minecraft/world/level/Level;Ldev/ryanhcode/sable/network/packets/PacketReceiveMode;)V",
            at=@At("HEAD"),remap=false,require=1)
    private void pdd$traceUnmappedPlot(Level level,PacketReceiveMode mode,CallbackInfo ci) {
        if(!RogTrace.clientEnabled())return;
        try {
            RogTrace.clientLevel(level);
            SubLevelContainer container=SubLevelContainer.getContainer(level);
            int missing=0;
            var dimension=level.dimension().location();
            for(var entry:entries) {
                int x=ChunkPos.getX(entry.plotCoordinate());
                int z=ChunkPos.getZ(entry.plotCoordinate());
                if(container!=null && container.getSubLevel(x,z) instanceof ClientSubLevel sub){
                    RogTrace.client("SNAPSHOT_MAPPED","clientDimension={} interpolationTick={} mode={} plotChunkX={} plotChunkZ={} ownerUUID={} outcome=MAPPED_BEFORE_HANDLER",dimension,interpolationTick,mode,x,z,sub.getUniqueId());
                    continue;
                }
                missing++;
                RogTrace.client("SNAPSHOT_MISSING","clientDimension={} interpolationTick={} mode={} plotChunkX={} plotChunkZ={} containerPresent={} ownerUUID=UNAVAILABLE runtimeBodyId=UNAVAILABLE action=SABLE_HANDLER_UNCHANGED",dimension,interpolationTick,mode,x,z,container!=null);
            }
            RogTrace.client(missing==0?"SNAPSHOT_ALL_PRESENT":"SNAPSHOT_WITH_MISSING","clientDimension={} interpolationTick={} mode={} entries={} missing={} mapped={} containerPresent={}",dimension,interpolationTick,mode,entries.size(),missing,entries.size()-missing,container!=null);
        }catch(RuntimeException|LinkageError ignored) { /* never alter Sable packet handling */ }
    }
    @Inject(method="handleClient(Lnet/minecraft/world/level/Level;Ldev/ryanhcode/sable/network/packets/PacketReceiveMode;)V",
            at=@At("RETURN"),remap=false,require=1)
    private void pdd$traceSnapshotReturn(Level level,PacketReceiveMode mode,CallbackInfo ci){
        if(!RogTrace.clientEnabled())return;
        try{RogTrace.client("SNAPSHOT_RETURN","clientDimension={} interpolationTick={} mode={} entries={} outcome=RETURN_ONLY_NOT_SUCCESS_PROOF",level.dimension().location(),interpolationTick,mode,entries.size());}
        catch(RuntimeException|LinkageError ignored){}
    }
}
