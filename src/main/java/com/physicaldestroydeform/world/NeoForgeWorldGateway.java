package com.physicaldestroydeform.world;

import com.mojang.authlib.GameProfile;
import com.physicaldestroydeform.state.SparseStateKey;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.level.BlockEvent;

/** NeoForge 1.21.1 loaded-chunk World adapter. No chunk generation or Plot mutation. */
public final class NeoForgeWorldGateway implements WorldCommitCore.WorldGateway {
    private static final GameProfile ACTOR=new GameProfile(
            UUID.nameUUIDFromBytes("PDD:WORLD_COMMIT:0.4.1".getBytes(StandardCharsets.UTF_8)),
            "[PDD] Automation");
    private final MinecraftServer server;
    private final WorldCommitService lifecycle;
    public NeoForgeWorldGateway(MinecraftServer server,WorldCommitService lifecycle) {
        this.server=server;this.lifecycle=lifecycle;
    }
    public void clearPermissionCache(){ /* No shared authorization cache across intents. */ }
    @Override public boolean isServerThread(){return server.isSameThread();}
    private ServerLevel dimension(String dim){
        for(ServerLevel level:server.getAllLevels())
            if(level.dimension().location().toString().equals(dim))return level;
        return null;
    }
    @Override public WorldCommitCore.Observation readIfLoaded(SparseStateKey key) {
        if(!isServerThread() || !"WORLD".equals(key.ownerSpace()))return null;
        ServerLevel level=dimension(key.dimension());
        if(level==null)return null;
        BlockPos pos=new BlockPos(key.x(),key.y(),key.z());
        if(!level.isInWorldBounds(pos) || level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4)==null)
            return null;
        try {
            var chunk=new ChunkPos(pos);
            var container=SubLevelContainer.getContainer(level);
            // A Plot storage cell must never be treated as static World, even at matching XYZ.
            if(container==null || container.getPlot(chunk)!=null || container.inBounds(chunk))return null;
            BlockState state=level.getBlockState(pos);
            String fp=BuiltInRegistries.BLOCK.getKey(state.getBlock())+"|"+state;
            boolean blockEntity=state.hasBlockEntity() || level.getBlockEntity(pos)!=null;
            boolean fullCube=state.isCollisionShapeFullBlock(level,pos);
            boolean unsafe=!fullCube || state.hasAnalogOutputSignal() || !isSimpleState(state);
            boolean unbreakable=state.getDestroySpeed(level,pos)<0;
            return new WorldCommitCore.Observation(key.dimension(),fp,1,lifecycle.incarnation(key.dimension(),pos,fp),
                    true,state.isAir(),blockEntity,unsafe,unbreakable,false);
        } catch(RuntimeException unsafe){return null;}
    }
    /** Conservative safety policy: only single-cell full cubes, no block entities or dynamic blocks. */
    private static boolean isSimpleState(BlockState state){
        String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if(!id.startsWith("minecraft:"))return false;
        String block=id.substring("minecraft:".length());
        return block.equals("stone") || block.equals("cobblestone") || block.equals("deepslate")
                || block.equals("cobbled_deepslate") || block.equals("tuff")
                || block.equals("dirt") || block.equals("grass_block") || block.equals("sand")
                || block.equals("gravel") || block.equals("netherrack") || block.equals("end_stone")
                || block.equals("obsidian") || block.equals("bricks")
                || block.endsWith("_planks") || block.endsWith("_concrete")
                || block.endsWith("_terracotta") || block.equals("terracotta")
                || block.endsWith("_wool") || block.endsWith("_bricks")
                || block.equals("stone_bricks") || block.equals("smooth_stone")
                || block.equals("andesite") || block.equals("granite") || block.equals("diorite");
    }
    @Override public WorldCommitCore.Permission canDamage(SparseStateKey key,WorldCommitCore.Observation expected) {
        if(!isServerThread())return WorldCommitCore.Permission.UNKNOWN;
        var seen=readIfLoaded(key);
        if(!expected.equals(seen) || seen.hasBlockEntity() || seen.unsafeMultiblock() || seen.unbreakable())
            return WorldCommitCore.Permission.DENY;
        try {
            ServerLevel level=dimension(key.dimension());
            BlockPos pos=new BlockPos(key.x(),key.y(),key.z());
            var player=FakePlayerFactory.get(level,ACTOR);
            if(!level.mayInteract(player,pos) || !player.mayBuild())return WorldCommitCore.Permission.DENY;
            // A damage-only plan does not break a block. Dispatching a BreakEvent
            // here would trigger unrelated protection, drop and audit handlers even
            // when the block remains intact. Reserve BreakEvent for real breaks.
            return WorldCommitCore.Permission.ALLOW;
        }catch(RuntimeException|LinkageError unsafe){return WorldCommitCore.Permission.UNKNOWN;}
    }
    @Override public WorldCommitCore.Permission canBreak(SparseStateKey key,WorldCommitCore.Observation expected) {
        if(!isServerThread())return WorldCommitCore.Permission.UNKNOWN;
        var observed=readIfLoaded(key);
        if(!expected.equals(observed) || observed.isAir() || observed.hasBlockEntity()
                || observed.unsafeMultiblock() || observed.unbreakable())
            return WorldCommitCore.Permission.DENY;
        try {
            ServerLevel level=dimension(key.dimension());
            BlockPos pos=new BlockPos(key.x(),key.y(),key.z());
            var player=FakePlayerFactory.get(level,ACTOR);
            if(!level.mayInteract(player,pos) || !player.mayBuild())return WorldCommitCore.Permission.DENY;
            // The approved break is committed through ServerLevel.destroyBlock.
            // A single explicit BreakEvent lets claim/protection mods veto first.
            BlockEvent.BreakEvent veto=new BlockEvent.BreakEvent(level,pos,level.getBlockState(pos),player);
            NeoForge.EVENT_BUS.post(veto);
            return veto.isCanceled()?WorldCommitCore.Permission.DENY:WorldCommitCore.Permission.ALLOW;
        }catch(RuntimeException|LinkageError unsafe){return WorldCommitCore.Permission.UNKNOWN;}
    }
    @Override public boolean breakVanillaOnce(SparseStateKey key) {
        if(!isServerThread())return false;
        ServerLevel level=dimension(key.dimension());
        if(level==null || readIfLoaded(key)==null)return false;
        BlockPos pos=new BlockPos(key.x(),key.y(),key.z());
        // Level#destroyBlock performs drops itself; NEVER call Block.dropResources manually.
        lifecycle.beginOwnBreak(key);
        try {return level.destroyBlock(pos,true);}
        finally {lifecycle.endOwnBreak();}
    }
}
