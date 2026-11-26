package com.physicaldestroydeform.mixin;

import com.physicaldestroydeform.diagnostics.RogTrace;
import com.physicaldestroydeform.diagnostics.RogTrace.Stage;
import dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotDualPacket;
import dev.ryanhcode.sable.network.udp.SableUDPPacket;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Record original UDP return value without claiming delivery or mutating packets. */
@Mixin(targets="dev.ryanhcode.sable.network.udp.SableUDPServer",remap=false)
public abstract class SableUdpSendDiagnosticMixin {
    @Inject(method="sendUDPPacket(Lnet/minecraft/server/level/ServerPlayer;Ldev/ryanhcode/sable/network/udp/SableUDPPacket;Z)Z",at=@At("RETURN"),remap=false,require=1)
    private void pdd$sendResult(ServerPlayer player,SableUDPPacket packet,boolean flag,CallbackInfoReturnable<Boolean> cir){
        if(!(packet instanceof ClientboundSableSnapshotDualPacket snapshot)||!RogTrace.sample(Stage.R12,"SERVER_UDP_RESULT"))return;
        try{
            int tick=((SableSnapshotTickAccessor)(Object)snapshot).pdd$interpolationTick();
            RogTrace.detail(Stage.R12,"SERVER_UDP_RESULT","gameTick={} dim={} player={} interpolationTick={} entries={} sampleCoordinates={} originalReturn={} delivery=UNVERIFIED",player.level().getGameTime(),player.level().dimension().location(),player.getUUID(),tick,snapshot.entries().size(),snapshot.entries().stream().limit(8).map(ClientboundSableSnapshotDualPacket.Entry::plotCoordinate).toList(),cir.getReturnValue());
        }catch(RuntimeException|LinkageError ignored){}
    }
}
