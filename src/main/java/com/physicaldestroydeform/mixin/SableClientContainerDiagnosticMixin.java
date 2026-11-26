package com.physicaldestroydeform.mixin;

import com.physicaldestroydeform.diagnostics.RogTrace;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets="dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer",remap=false)
public abstract class SableClientContainerDiagnosticMixin {
    @Inject(method="<init>(Lnet/minecraft/world/level/Level;IIII)V",at=@At("RETURN"),remap=false,require=1)
    private void pdd$traceContainer(Level level,int a,int b,int c,int d,CallbackInfo ci){
        if(!RogTrace.clientEnabled())return;
        try{RogTrace.clientLevel(level);RogTrace.client("CLIENT_CONTAINER_CREATED","dimension={}",level.dimension().location());}
        catch(RuntimeException|LinkageError ignored){}
    }
}
