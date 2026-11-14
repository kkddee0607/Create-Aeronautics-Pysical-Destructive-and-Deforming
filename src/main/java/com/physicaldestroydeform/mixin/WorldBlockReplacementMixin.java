package com.physicaldestroydeform.mixin;

import com.physicaldestroydeform.world.WorldBlockMutationTracker;
import com.physicaldestroydeform.plot.PlotBreakMutationWitness;
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

/** Invalidate tracked damage only when a vanilla/Sable/other mod state change succeeds. */
@Mixin(LevelChunk.class)
public abstract class WorldBlockReplacementMixin {
    @Shadow @Final Level level;
    @Inject(method="setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at=@At("RETURN"))
    private void pdd$replace(BlockPos pos,BlockState state,boolean moved,CallbackInfoReturnable<BlockState> ci) {
        // LevelChunk#setBlockState returns null on a no-op. A canceled/no-op update
        // must not erase an unrelated persisted damage state.
        BlockState previous=ci.getReturnValue();
        if(level.isClientSide() || previous==null || previous.equals(state))return;
        // Capture the completed real chunk mutation before owner lookup can fail
        // after Sable removes/splits its Plot during the very same call.
        PlotBreakMutationWitness.observe(level,pos,previous,state,(LevelChunk)(Object)this);
        WorldBlockMutationTracker.onSetBlockState(level,pos,previous,state);
    }
}
