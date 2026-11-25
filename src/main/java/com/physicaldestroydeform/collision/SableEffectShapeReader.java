package com.physicaldestroydeform.collision;

import dev.ryanhcode.sable.api.block.BlockSubLevelCollisionShape;
import dev.ryanhcode.sable.api.physics.collider.SableCollisionContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.KelpBlock;
import net.minecraft.world.level.block.KelpPlantBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Read-only counterpart of Sable 2.0.6 RapierVoxelColliderBakery's voxel shape
 * query. Its PhysicsColliderBlockGetter is a nested Rapier class unavailable on
 * PDD's compileClasspath; reproduce the observed API contract instead of
 * importing that nested implementation or creating native collision bodies.
 */
final class SableEffectShapeReader {
    private SableEffectShapeReader() {}
    static VoxelShape read(BlockGetter owner,BlockState state){
        if(owner==null||state==null)return net.minecraft.world.phys.shapes.Shapes.empty();
        // Sable VoxelNeighborhoodState.isLiquid() bakes these as a full box.
        if(state.liquid()||state.getBlock() instanceof KelpPlantBlock||state.getBlock() instanceof KelpBlock)
            return net.minecraft.world.phys.shapes.Shapes.block();
        BlockGetter isolated=new BlockGetter(){
            @Override public BlockEntity getBlockEntity(BlockPos pos){return null;}
            @Override public BlockState getBlockState(BlockPos pos){return BlockPos.ZERO.equals(pos)?state:Blocks.AIR.defaultBlockState();}
            @Override public FluidState getFluidState(BlockPos pos){return BlockPos.ZERO.equals(pos)?state.getFluidState():Fluids.EMPTY.defaultFluidState();}
            @Override public int getHeight(){return owner.getHeight();}
            @Override public int getMinBuildHeight(){return owner.getMinBuildHeight();}
        };
        if(state.getBlock() instanceof BlockSubLevelCollisionShape custom)
            return custom.getSubLevelCollisionShape(isolated,state);
        return state.getCollisionShape(isolated,BlockPos.ZERO,SableCollisionContext.get());
    }
}
