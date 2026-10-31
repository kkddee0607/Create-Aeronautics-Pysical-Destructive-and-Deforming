package com.physicaldestroydeform.collision;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;
import java.util.Map;
import static com.physicaldestroydeform.collision.CollisionFrame.*;

/**
 * Read-only owner/block audit on the SERVER THREAD after the physics callback.
 * A Plot's chunk allocation is only a candidate until the authoritative Sable
 * container points back to the same stable sublevel and an actual block exists.
 * This yields the future damage/mutation TargetKey without mutating either world.
 */
public final class OwnerResolver {
    private OwnerResolver() {}
    public enum Status { VERIFIED, NO_TARGET, OWNER_UNRESOLVED, OWNER_CHANGED,
        STALE_STEP, BLOCK_UNAVAILABLE, BLOCK_MISMATCH }
    public record TargetKey(String dimension, Space space, UUID stablePlotId,
                            int x, int y, int z, String fingerprint) {
        public TargetKey {
            if (dimension==null || dimension.isBlank() || space==null || space == Space.UNKNOWN
                    || (space == Space.SABLE_PLOT) != (stablePlotId != null)
                    || fingerprint == null || fingerprint.isBlank())
                throw new IllegalArgumentException("unverified target identity");
        }
    }
    public record Check(Status status, TargetKey key, String detail, boolean fullWorldCollisionCube) {
        /** Keep the original three-argument test/contract constructor. */
        public Check(Status status, TargetKey key, String detail) { this(status,key,detail,false); }
        public boolean verified() { return status == Status.VERIFIED && key != null; }
        static Check failed(Status status, String detail) { return new Check(status, null, detail); }
    }
    public record Pair(Check hit, Check other) {
        public boolean bothVerified() { return hit.verified() && other.verified(); }
    }

    public record CacheKey(BlockTarget proposed, String expectedBlockId) {}

    /** Cache is scoped to a single server tick; never reuse stale block fingerprints. */
    public static Pair audit(MinecraftServer server, Evidence evidence, String callbackBlockId,
                             Map<CacheKey, Check> cache) {
        if(evidence==null || server==null)
            return new Pair(Check.failed(Status.NO_TARGET,"NO_EVIDENCE"),
                    Check.failed(Status.NO_TARGET,"NO_EVIDENCE"));
        try {
        ServerLevel level=null;
        for (ServerLevel candidate : server.getAllLevels())
            if (candidate.dimension().location().toString().equals(evidence.dimension())) {
                level=candidate;
                break;
            }
        if(level==null)
            return new Pair(Check.failed(Status.OWNER_UNRESOLVED,"DIMENSION_MISSING"),
                    Check.failed(Status.OWNER_UNRESOLVED,"DIMENSION_MISSING"));
        if(evidence.phase()!=Phase.PRE_SOLVER || Math.abs(level.getGameTime()-evidence.serverTick())>1)
            return new Pair(Check.failed(Status.STALE_STEP,"NOT_CURRENT_PRE_SOLVER_STEP"),
                    Check.failed(Status.STALE_STEP,"NOT_CURRENT_PRE_SOLVER_STEP"));
        final ServerLevel currentLevel=level;
        var container=SubLevelContainer.getContainer(currentLevel);
        Check hit=cache.computeIfAbsent(new CacheKey(evidence.hit(),callbackBlockId),
                key->check(currentLevel,container,key.proposed(),key.expectedBlockId()));
        Check other=cache.computeIfAbsent(new CacheKey(evidence.other(),null),
                key->check(currentLevel,container,key.proposed(),key.expectedBlockId()));
        return new Pair(hit,other);
        } catch(RuntimeException unexpected) {
            // A diagnostic subscriber must never destabilize the vanilla/Sable tick.
            return new Pair(Check.failed(Status.OWNER_UNRESOLVED,"OWNER_AUDIT_EXCEPTION"),
                    Check.failed(Status.OWNER_UNRESOLVED,"OWNER_AUDIT_EXCEPTION"));
        }
    }

    /** Converts allocated Plot-grid BlockPos to EmbeddedPlotLevelAccessor's centered coordinates. */
    public static BlockPos toEmbeddedPosition(BlockPos allocated, BlockPos plotCenter) {
        return new BlockPos(allocated.getX()-plotCenter.getX(),
                allocated.getY()-plotCenter.getY(),allocated.getZ()-plotCenter.getZ());
    }

    private static Check check(ServerLevel level, dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container,
                               BlockTarget proposed, String expectedBlockId) {
        if(proposed==null) return Check.failed(Status.NO_TARGET,"OTHER_NOT_PRESENT");
        if(!proposed.valid() || container==null || proposed.space()==Space.UNKNOWN)
            return Check.failed(Status.OWNER_UNRESOLVED, proposed.provenance());
        BlockPos pos=new BlockPos(proposed.x(),proposed.y(),proposed.z());
        ChunkPos chunk=new ChunkPos(pos);
        LevelPlot actualPlot;
        try { actualPlot=container.getPlot(chunk); }
        catch(RuntimeException ex) { return Check.failed(Status.OWNER_UNRESOLVED,"PLOT_LOOKUP_EXCEPTION"); }
        BlockState state;
        if(proposed.space()==Space.SABLE_PLOT) {
            if(actualPlot==null || !(actualPlot.getSubLevel() instanceof ServerSubLevel sub)
                    || sub.isRemoved() || sub.getLevel()!=level || sub.getPlot()!=actualPlot
                    || container.getSubLevel(proposed.stablePlotId())!=sub
                    || !sub.getUniqueId().equals(proposed.stablePlotId()))
                return Check.failed(Status.OWNER_CHANGED,"PLOT_ID_NOT_MAPPED_TO_CHUNK");
            // EmbeddedPlotLevelAccessor is CENTER-RELATIVE. Native callbacks carry
            // the allocated, absolute plot-grid BlockPos. Never pass it directly:
            // the accessor internally adds getCenterBlock()/getCenterChunk().
            BlockPos center=actualPlot.getCenterBlock();
            ChunkPos centerChunk=actualPlot.getCenterChunk();
            BlockPos relative=toEmbeddedPosition(pos,center);
            if(!actualPlot.getEmbeddedLevelAccessor().hasChunk(
                    chunk.x-centerChunk.x,chunk.z-centerChunk.z))
                return Check.failed(Status.BLOCK_UNAVAILABLE,"PLOT_CHUNK_UNLOADED");
            try { state=actualPlot.getEmbeddedLevelAccessor().getBlockState(relative); }
            catch(RuntimeException ex) {return Check.failed(Status.BLOCK_UNAVAILABLE,"PLOT_BLOCK_READ_FAILED");}
        } else {
            if(actualPlot!=null || container.inBounds(chunk))
                return Check.failed(Status.OWNER_UNRESOLVED,"PLOT_AREA_MUST_NOT_FALLBACK_TO_WORLD");
            if(!level.hasChunkAt(pos)) return Check.failed(Status.BLOCK_UNAVAILABLE,"WORLD_CHUNK_UNLOADED");
            try {state=level.getBlockState(pos);}
            catch(RuntimeException ex) {return Check.failed(Status.BLOCK_UNAVAILABLE,"WORLD_BLOCK_READ_FAILED");}
        }
        if(state==null || state.isAir())return Check.failed(Status.BLOCK_UNAVAILABLE,"BLOCK_MISSING_OR_AIR");
        String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if(expectedBlockId!=null && !id.equals(expectedBlockId))
            return Check.failed(Status.BLOCK_MISMATCH,"CALLBACK_BLOCK_ID_DIFFERENT_FROM_LIVE_STATE");
        return new Check(Status.VERIFIED,
                new TargetKey(proposed.dimension(),proposed.space(),proposed.stablePlotId(),
                        proposed.x(),proposed.y(),proposed.z(),id+"|"+state),"LIVE_OWNER_AND_BLOCK_VERIFIED",
                proposed.space()==Space.STATIC_WORLD && state.isCollisionShapeFullBlock(level,pos));
    }
}
