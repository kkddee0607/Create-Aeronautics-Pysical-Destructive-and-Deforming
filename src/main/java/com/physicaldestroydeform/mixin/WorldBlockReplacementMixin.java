package com.physicaldestroydeform.mixin;

import com.physicaldestroydeform.world.WorldBlockMutationTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Mark tracked damage stale before vanilla/Sable/other mod block replacement. */
@Mixin(LevelChunk.class)
public abstract class WorldBlockReplacementMixin {
    @Shadow @Final Level level;
    @Inject(method="setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at=@At("HEAD"))
    private void pdd$replace(BlockPos pos,BlockState state,boolean moved,CallbackInfoReturnable<BlockState> ci) {
        if(level.isClientSide())return;
        WorldBlockMutationTracker.onSetBlockState(level,pos,
                ((LevelChunk)(Object)this).getBlockState(pos),state);
    }
}
