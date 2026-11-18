package com.physicaldestroydeform.deform;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import com.physicaldestroydeform.deform.MoveTransactionExecutor.Gate;

/** Shared loaded-cell geometry for World/Plot. The caller proves owner identity separately.
 * Create 6.0.10 API signatures checked against the supplied JAR; optional reflection avoids
 * introducing a Create compile/runtime dependency into the pure JUnit classpath. */
public final class MoveSafety {
    private MoveSafety() {}
    public static List<BlockPos> path(BlockPos source,BlockPos destination) {
        long dx=(long)destination.getX()-source.getX(),dy=(long)destination.getY()-source.getY(),
                dz=(long)destination.getZ()-source.getZ();
        long length=Math.abs(dx)+Math.abs(dy)+Math.abs(dz);
        if(Math.abs(dx)>1||Math.abs(dy)>1||Math.abs(dz)>1||length<1||length>2)
            throw new IllegalArgumentException("expected signed one/two-axis movement");
        List<BlockPos> result=new ArrayList<>(3);
        if(length==2) {
            if(dx!=0)result.add(source.offset((int)dx,0,0));
            if(dz!=0)result.add(source.offset(0,0,(int)dz));
            if(dy!=0)result.add(source.offset(0,(int)dy,0));
        }
        result.add(destination.immutable());return List.copyOf(result);
    }
    public static boolean loaded(ServerLevel level,BlockPos pos) {
        return level.isInWorldBounds(pos)&&level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4)!=null;
    }
    /** Only shapes contained within one voxel can use the one/two-axis swept-cell proof. */
    public static boolean unitShape(ServerLevel level,BlockPos pos,BlockState state){
        var shape=state.getCollisionShape(level,pos);
        if(shape.isEmpty())return true;
        AABB b=shape.bounds();
        return b.minX>=0&&b.minY>=0&&b.minZ>=0&&b.maxX<=1&&b.maxY<=1&&b.maxZ<=1;
    }
    /** Air or a vanilla replaceable, collision-free, non-fluid and payload-free cell.
     * Arbitrary modded replaceables may execute side-effects on removal and need an adapter. */
    public static boolean clearPathCell(ServerLevel level,BlockPos pos,BlockState state){
        if(state.hasBlockEntity()||level.getBlockEntity(pos)!=null||!state.getFluidState().isEmpty())return false;
        return state.isAir()||state.canBeReplaced()&&"minecraft".equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace())
                &&state.getCollisionShape(level,pos).isEmpty();
    }
    private static final class CreateChecks {
        static final Method ALLOWED,ATTACHED;
        static final Class<? extends Entity> GLUE;
        static final Class<?> KINETIC;
        static {
            try {
                Class<?> c=Class.forName("com.simibubi.create.api.contraption.BlockMovementChecks");
                ALLOWED=c.getMethod("isMovementAllowed",BlockState.class,Level.class,BlockPos.class);
                ATTACHED=c.getMethod("isBlockAttachedTowards",BlockState.class,Level.class,BlockPos.class,Direction.class);
                GLUE=Class.forName("com.simibubi.create.content.contraptions.glue.SuperGlueEntity").asSubclass(Entity.class);
                KINETIC=Class.forName("com.simibubi.create.content.kinetics.base.KineticBlockEntity");
            }catch(ReflectiveOperationException e){throw new ExceptionInInitializerError(e);}
        }
    }
    /** Read-only and bounded. A connected machine, glue, fragile support or sticky block is not
     * independently relocatable; unknown checks never authorize mutation. Copycat BE approval
     * is a separate payload check in each owner gateway. */
    public static Gate checkConnections(ServerLevel level,BlockPos pos,BlockState state) {
        if(!loaded(level,pos))return Gate.DEFERRED;
        for(Direction side:Direction.values())if(!loaded(level,pos.relative(side)))return Gate.DEFERRED;
        try {
            if(!state.isAir()&&(state.getPistonPushReaction()!=PushReaction.NORMAL||state.getBlock() instanceof FallingBlock
                    ||sticky(state)||!state.getFluidState().isEmpty()||!unitShape(level,pos,state)||CreateChecks.KINETIC.isInstance(level.getBlockEntity(pos))
                    ||!((Boolean)CreateChecks.ALLOWED.invoke(null,state,level,pos))))return Gate.BLOCKED;
            // Query the real glue entities (collectCropped would create temporary entities).
            AABB area=new AABB(pos.getX(),pos.getY(),pos.getZ(),pos.getX()+1,pos.getY()+1,pos.getZ()+1);
            if(!level.getEntitiesOfClass(CreateChecks.GLUE,area.inflate(0.001)).isEmpty())return Gate.BLOCKED;
            for(Direction side:Direction.values()) {
                BlockPos adjacent=pos.relative(side);BlockState neighbor=level.getBlockState(adjacent);
                if(!state.isAir()&&((Boolean)CreateChecks.ATTACHED.invoke(null,state,level,pos,side)))return Gate.BLOCKED;
                if(neighbor.isAir())continue;
                if(sticky(neighbor)||neighbor.getBlock() instanceof FallingBlock
                        ||CreateChecks.KINETIC.isInstance(level.getBlockEntity(adjacent))
                        ||((Boolean)CreateChecks.ATTACHED.invoke(null,neighbor,level,adjacent,side.getOpposite())))return Gate.BLOCKED;
            }
            return Gate.APPROVED;
        }catch(ReflectiveOperationException|RuntimeException|LinkageError unsafe){return Gate.UNKNOWN;}
    }
    private static boolean sticky(BlockState state){return state.is(Blocks.SLIME_BLOCK)||state.is(Blocks.HONEY_BLOCK);}
}
