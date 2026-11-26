package com.physicaldestroydeform.mixin;

import com.physicaldestroydeform.diagnostics.RogTrace;
import com.physicaldestroydeform.diagnostics.RogTrace.Stage;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe the exact 2.0.6 full-sync entry, not wire delivery. */
@Mixin(targets="dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem",remap=false)
public abstract class SableTrackingSendDiagnosticMixin {
    @Shadow private static long getSubLevelLong(ServerSubLevel sub,SubLevelContainer container){throw new AssertionError();}
    @Inject(method="sendFullSync(Lnet/minecraft/server/level/ServerPlayer;Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V",at=@At("HEAD"),remap=false,require=1)
    private void pdd$fullSync(ServerPlayer player,ServerSubLevel sub,CustomPacketPayload extra,CallbackInfo ci){
        if(!RogTrace.sample(Stage.R12,"SERVER_FULL_SYNC_ENTRY"))return;
        try{
            var c=SubLevelContainer.getContainer(sub.getLevel());if(c==null)return;
            long pos=getSubLevelLong(sub,c);
            RogTrace.detail(Stage.R12,"SERVER_FULL_SYNC_ENTRY","gameTick={} dim={} player={} ownerUUID={} plotChunkX={} plotChunkZ={} outcome=ENTRY_NOT_WIRE_SEND",sub.getLevel().getGameTime(),sub.getLevel().dimension().location(),player.getUUID(),sub.getUniqueId(),ChunkPos.getX(pos),ChunkPos.getZ(pos));
        }catch(RuntimeException|LinkageError ignored){}
    }
}
