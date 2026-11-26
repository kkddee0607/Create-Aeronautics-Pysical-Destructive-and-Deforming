package com.physicaldestroydeform.mixin;

import com.physicaldestroydeform.diagnostics.RogTrace;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Packet HEAD/RETURN only; callback-only signature deliberately avoids a Veil compile dependency.
 * A RETURN may be an early rejection, not proof of a successful client mutation. */
@Mixin(targets="dev.ryanhcode.sable.network.packets.tcp.ClientboundStopTrackingSubLevelPacket",remap=false)
public abstract class SableStopTrackingPacketDiagnosticMixin {
    @Shadow @Final private long plotCoordinate;
    @Inject(method="handle(Lfoundry/veil/api/network/handler/PacketContext;)V",at=@At("HEAD"),remap=false,require=1)
    private void pdd$traceHead(CallbackInfo ci){
        if(!RogTrace.clientEnabled())return;
        try{RogTrace.client("STOP_TRACKING_HEAD","plotChunkX={} plotChunkZ={} clientDimension=UNOBSERVED outcome=ENTRY_ONLY",ChunkPos.getX(plotCoordinate),ChunkPos.getZ(plotCoordinate));}
        catch(RuntimeException|LinkageError ignored){}
    }
    @Inject(method="handle(Lfoundry/veil/api/network/handler/PacketContext;)V",at=@At("RETURN"),remap=false,require=1)
    private void pdd$traceReturn(CallbackInfo ci){
        if(!RogTrace.clientEnabled())return;
        try{RogTrace.client("STOP_TRACKING_RETURN","plotChunkX={} plotChunkZ={} clientDimension=UNOBSERVED outcome=RETURN_ONLY_NOT_SUCCESS_PROOF",ChunkPos.getX(plotCoordinate),ChunkPos.getZ(plotCoordinate));}
        catch(RuntimeException|LinkageError ignored){}
    }
}
