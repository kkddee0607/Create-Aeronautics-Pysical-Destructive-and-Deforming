package com.physicaldestroydeform.mixin;

import com.physicaldestroydeform.world.NativeLineageTransfer;
import com.physicaldestroydeform.world.WorldBlockMutationTracker;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper.AssemblyTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Unchanged native argument list, callback order, return and movement behavior. */
@Mixin(value=SubLevelAssemblyHelper.class,remap=false)
public abstract class SableAssemblyLineageMixin {
    @Inject(method="moveBlocks(Lnet/minecraft/server/level/ServerLevel;Ldev/ryanhcode/sable/api/SubLevelAssemblyHelper$AssemblyTransform;Ljava/lang/Iterable;)V",
            at=@At("HEAD"),require=1,remap=false)
    private static void pdd$before(ServerLevel level,AssemblyTransform transform,Iterable<BlockPos> blocks,CallbackInfo ci){
        WorldBlockMutationTracker.beginNativeLineage(level,transform,blocks);
    }
    // Sable 2.0.6 moveBlocks offsets 335/343: exact pair and rotated state,
    // after destination chunks are allocated and before any native setter.
    @Redirect(method="moveBlocks(Lnet/minecraft/server/level/ServerLevel;Ldev/ryanhcode/sable/api/SubLevelAssemblyHelper$AssemblyTransform;Ljava/lang/Iterable;)V",
            at=@At(value="INVOKE",target="Ldev/ryanhcode/sable/api/SubLevelAssemblyHelper$AssemblyTransform;apply(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;",ordinal=2),
            require=1,remap=false)
    private static BlockPos pdd$mappedPosition(AssemblyTransform transform,BlockPos from){
        BlockPos to=transform.apply(from);NativeLineageTransfer.mapping(from,to);return to;
    }
    @Redirect(method="moveBlocks(Lnet/minecraft/server/level/ServerLevel;Ldev/ryanhcode/sable/api/SubLevelAssemblyHelper$AssemblyTransform;Ljava/lang/Iterable;)V",
            at=@At(value="INVOKE",target="Ldev/ryanhcode/sable/api/SubLevelAssemblyHelper$AssemblyTransform;apply(Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;"),
            require=1,remap=false)
    private static BlockState pdd$mappedState(AssemblyTransform transform,BlockState old){
        BlockState rotated=transform.apply(old);NativeLineageTransfer.states(old,rotated);return rotated;
    }
    @Redirect(method="moveBlocks(Lnet/minecraft/server/level/ServerLevel;Ldev/ryanhcode/sable/api/SubLevelAssemblyHelper$AssemblyTransform;Ljava/lang/Iterable;)V",
            at=@At(value="INVOKE",target="Lnet/minecraft/world/level/block/entity/BlockEntity;saveWithFullMetadata(Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/nbt/CompoundTag;"),
            require=1,remap=false)
    private static CompoundTag pdd$nativePayload(BlockEntity be,HolderLookup.Provider provider){
        CompoundTag tag=be.saveWithFullMetadata(provider);NativeLineageTransfer.payload(tag);return tag;
    }
    @Inject(method="moveBlocks(Lnet/minecraft/server/level/ServerLevel;Ldev/ryanhcode/sable/api/SubLevelAssemblyHelper$AssemblyTransform;Ljava/lang/Iterable;)V",
            at=@At("RETURN"),require=1,remap=false)
    private static void pdd$after(ServerLevel level,AssemblyTransform transform,Iterable<BlockPos> blocks,CallbackInfo ci){
        NativeLineageTransfer.finish(level,transform,blocks);
    }
}
