package com.physicaldestroydeform.deform;

import com.physicaldestroydeform.diagnostics.RogTrace;
import com.physicaldestroydeform.diagnostics.RogTrace.Stage;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
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
    /** Exact metadata types from the pinned Create and bundled Simulated JARs.
     * Do not infer this from collision box, namespace, invisibility or a tag. */
    public static boolean isAssemblyGlue(String className,String typeId){
        return "com.simibubi.create.content.contraptions.glue.SuperGlueEntity".equals(className)
                &&"create:super_glue".equals(typeId)
                ||"dev.simulated_team.simulated.content.entities.honey_glue.HoneyGlueEntity".equals(className)
                &&"simulated:honey_glue".equals(typeId);
    }
    public static Gate plotEntityGate(boolean knownGlue,boolean sameOwner){
        return knownGlue?(sameOwner?Gate.APPROVED:Gate.UNKNOWN):Gate.BLOCKED;
    }
    /** Sable allocation is an axis-aligned rectangular chunk grid (getPlot).
     * Check the anchor and four half-open horizontal corners, never load chunks. */
    public static boolean glueWithinPlot(AABB bounds,double anchorX,double anchorZ,Predicate<ChunkPos> sameOwner){
        if(bounds==null||!Double.isFinite(bounds.minY)||!Double.isFinite(bounds.maxY)
                ||!(bounds.maxX>bounds.minX&&bounds.maxY>bounds.minY&&bounds.maxZ>bounds.minZ))return false;
        double endX=Math.nextDown(bounds.maxX),endZ=Math.nextDown(bounds.maxZ);
        return ownChunk(anchorX,anchorZ,sameOwner)&&ownChunk(bounds.minX,bounds.minZ,sameOwner)
                &&ownChunk(bounds.minX,endZ,sameOwner)&&ownChunk(endX,bounds.minZ,sameOwner)
                &&ownChunk(endX,endZ,sameOwner);
    }
    private static boolean ownChunk(double x,double z,Predicate<ChunkPos> sameOwner){
        return Double.isFinite(x)&&Double.isFinite(z)&&x>=Integer.MIN_VALUE&&x<=Integer.MAX_VALUE
                &&z>=Integer.MIN_VALUE&&z<=Integer.MAX_VALUE
                &&sameOwner.test(new ChunkPos((int)Math.floor(x)>>4,(int)Math.floor(z)>>4));
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
        return checkConnections(level,pos,state,e->Gate.BLOCKED);
    }
    /** Plot-only caller may prove a glue region remains in the same allocation.
     * The World entry above retains the original unconditional glue veto. */
    public static Gate checkConnections(ServerLevel level,BlockPos pos,BlockState state,Function<Entity,Gate> glueGate) {
        if(!loaded(level,pos))return traceConnection(Gate.DEFERRED,"CONNECTION_SOURCE_UNLOADED",pos,null);
        for(Direction side:Direction.values())if(!loaded(level,pos.relative(side)))return traceConnection(Gate.DEFERRED,"CONNECTION_NEIGHBOR_UNLOADED",pos,side);
        try {
            if(!state.isAir()){
                if(state.getPistonPushReaction()!=PushReaction.NORMAL)return traceConnection(Gate.BLOCKED,"CONNECTION_PUSH_REACTION",pos,state);
                if(state.getBlock() instanceof FallingBlock)return traceConnection(Gate.BLOCKED,"CONNECTION_FALLING",pos,state);
                if(sticky(state))return traceConnection(Gate.BLOCKED,"CONNECTION_STICKY",pos,state);
                if(!state.getFluidState().isEmpty())return traceConnection(Gate.BLOCKED,"CONNECTION_FLUID",pos,state);
                if(!unitShape(level,pos,state))return traceConnection(Gate.BLOCKED,"CONNECTION_SHAPE",pos,state);
                if(CreateChecks.KINETIC.isInstance(level.getBlockEntity(pos)))return traceConnection(Gate.BLOCKED,"CONNECTION_KINETIC",pos,state);
                if(!((Boolean)CreateChecks.ALLOWED.invoke(null,state,level,pos)))return traceConnection(Gate.BLOCKED,"CONNECTION_CREATE_DISALLOWED",pos,state);
            }
            // Query the real glue entities (collectCropped would create temporary entities).
            AABB area=new AABB(pos.getX(),pos.getY(),pos.getZ(),pos.getX()+1,pos.getY()+1,pos.getZ()+1);
            for(Entity glue:level.getEntitiesOfClass(CreateChecks.GLUE,area.inflate(0.001))){
                Gate decision=glueGate.apply(glue);
                if(decision==null)return traceConnection(Gate.UNKNOWN,"CONNECTION_GLUE_UNKNOWN",pos,null);
                if(decision!=Gate.APPROVED)return traceConnection(decision,"CONNECTION_GLUE_VETO",pos,null);
            }
            for(Direction side:Direction.values()) {
                BlockPos adjacent=pos.relative(side);BlockState neighbor=level.getBlockState(adjacent);
                if(!state.isAir()&&((Boolean)CreateChecks.ATTACHED.invoke(null,state,level,pos,side)))return traceConnection(Gate.BLOCKED,"CONNECTION_SOURCE_ATTACHED",pos,side);
                if(neighbor.isAir())continue;
                if(sticky(neighbor))return traceConnection(Gate.BLOCKED,"CONNECTION_NEIGHBOR_STICKY",pos,side);
                if(neighbor.getBlock() instanceof FallingBlock)return traceConnection(Gate.BLOCKED,"CONNECTION_NEIGHBOR_FALLING",pos,side);
                if(CreateChecks.KINETIC.isInstance(level.getBlockEntity(adjacent)))return traceConnection(Gate.BLOCKED,"CONNECTION_NEIGHBOR_KINETIC",pos,side);
                if((Boolean)CreateChecks.ATTACHED.invoke(null,neighbor,level,adjacent,side.getOpposite()))return traceConnection(Gate.BLOCKED,"CONNECTION_NEIGHBOR_ATTACHED",pos,side);
            }
            return traceConnection(Gate.APPROVED,"CONNECTION_CLEAR",pos,null);
        }catch(ReflectiveOperationException|RuntimeException|LinkageError unsafe){return traceConnection(Gate.UNKNOWN,"CONNECTION_EXCEPTION",pos,unsafe.getClass().getName());}
    }
    private static Gate traceConnection(Gate result,String reason,BlockPos pos,Object detail){
        if(RogTrace.sample(Stage.R08,reason))RogTrace.detail(Stage.R08,reason,"allocatedPosition={} result={} detail={}",pos,result,detail);
        return result;
    }
    private static boolean sticky(BlockState state){return state.is(Blocks.SLIME_BLOCK)||state.is(Blocks.HONEY_BLOCK);}
}
