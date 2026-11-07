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
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.AABB;
import java.util.List;
import com.physicaldestroydeform.collision.FrameMath.Vec;

import java.util.UUID;
import java.util.HashMap;
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

    /** Source-verified native effect point in Sable's own allocated voxel grid.
     * Re-reads the actual owner/accessor after the physics step; never loads chunks. */
    public static Check auditNative(MinecraftServer server,String dimension,String stableOwner,BlockPos allocatedPos,
                                    Vec nativeOwnerPoint) {
        return auditNative(server,dimension,stableOwner,allocatedPos,nativeOwnerPoint,null);
    }
    private static final class ShapeCapture { VoxelShape value; }
    private static Check auditNative(MinecraftServer server,String dimension,String stableOwner,BlockPos allocatedPos,
                                     Vec nativeOwnerPoint,ShapeCapture capture) {
        if(server==null || dimension==null || stableOwner==null || allocatedPos==null)
            return Check.failed(Status.OWNER_UNRESOLVED,"BAD_NATIVE_OWNER_INPUT");
        try {
            ServerLevel level=null;
            for(var candidate:server.getAllLevels())
                if(candidate.dimension().location().toString().equals(dimension)) {level=candidate;break;}
            if(level==null)return Check.failed(Status.OWNER_UNRESOLVED,"DIMENSION_MISSING");
            Space space;
            UUID plotId=null;
            if(stableOwner.equals("WORLD:"+dimension))space=Space.STATIC_WORLD;
            else if(stableOwner.startsWith("PLOT:")) {
                space=Space.SABLE_PLOT;
                try {plotId=UUID.fromString(stableOwner.substring(5));}
                catch(IllegalArgumentException badId){return Check.failed(Status.OWNER_UNRESOLVED,"INVALID_PLOT_UUID");}
            }else return Check.failed(Status.OWNER_UNRESOLVED,"UNKNOWN_OWNER_SPACE");
            var container=SubLevelContainer.getContainer(level);
            var proposed=new BlockTarget(space,dimension,plotId,
                    allocatedPos.getX(),allocatedPos.getY(),allocatedPos.getZ(),true,
                    "SABLE_EFFECT_CONSUMER_ALLOCATED_GRID");
            return check(level,container,proposed,null,nativeOwnerPoint,capture);
        } catch(RuntimeException unsafe){return Check.failed(Status.OWNER_UNRESOLVED,"NATIVE_AUDIT_ERROR");}
    }

    /**
     * One native contact GROUP, one read-only snapshot of each owner/cell.
     * Reuse the expensive owner/block/shape lookup but NEVER reuse a point's
     * proximity result for another point. Caller charges only newly seen cells.
     * Do not retain across ticks, groups, or world mutation boundaries.
     */
    public static final class NativeAuditBatch {
        private final MinecraftServer server;
        private final String dimension;
        private final Map<CellKey, Cell> cells=new HashMap<>();
        private record CellKey(String owner,BlockPos pos) {}
        // Immutable geometry lists: no repeated VoxelShape.toAabbs allocations per witness.
        private record Cell(Check identity,List<AABB> boxes,BlockPos shapePos) {}
        public record Sample(Check result,boolean newCell) {}
        public record Stats(int newCells,long reusedCells,long pointTests,long testedBoxes) {}
        private long reusedCells,pointTests,testedBoxes;
        public NativeAuditBatch(MinecraftServer server,String dimension) {
            this.server=server;this.dimension=dimension;
        }
        public boolean cached(String owner,BlockPos pos) {
            return cells.containsKey(new CellKey(owner,pos));
        }
        public int cachedCells(){return cells.size();}
        public Stats stats(){return new Stats(cells.size(),reusedCells,pointTests,testedBoxes);}
        public Sample inspect(String owner,BlockPos pos,Vec point) {
            var key=new CellKey(owner,pos);
            Cell evidence=cells.get(key);
            boolean added=evidence==null;
            if(added) {
                // Single owner/block/shape snapshot. Never share across source groups.
                var captured=new ShapeCapture();
                Check basic=auditNative(server,dimension,owner,pos,null,captured);
                List<AABB> boxes=List.of();
                if(basic.verified()) {
                    if(captured.value==null || captured.value.isEmpty())
                        basic=Check.failed(Status.BLOCK_UNAVAILABLE,"CACHE_SHAPE_MISSING");
                    else boxes=List.copyOf(captured.value.toAabbs());
                }
                evidence=new Cell(basic,boxes,pos);
                cells.put(key,evidence);
            } else reusedCells++;
            Check structural=evidence.identity();
            if(!structural.verified())return new Sample(structural,added);
            pointTests++;
            testedBoxes+=evidence.boxes().size();
            if(!nearAabbs(evidence.boxes(),point,evidence.shapePos()))
                return new Sample(Check.failed(Status.BLOCK_MISMATCH,"CACHED_SHAPE_POINT_MISS"),added);
            return new Sample(structural,added);
        }
    }

    /** Read-only six-neighbor propagation evidence. Local WORLD coordinates are
     * world block coordinates; local PLOT coordinates are Sable embedded-accessor
     * relative coordinates. Do not load chunks or invent the next block. */
    /** Scope Plot storage-center identity to ONE approved impact plan. The
     * physics normal was frozen at the effect epoch; the owner-local lattice
     * must not silently jump to a different Sable storage allocation while
     * traversing its children. Do not reproject later layers from world pose. */
    private record LocalCell(String owner,int x,int y,int z) {}
    private record PlotSnapshot(UUID id,BlockPos center) {}
    /** One approved credit, one immutable-read epoch on the SERVER thread.
     * Cache the owner/coordinate proof, not an across-tick world view. Each
     * first visit still executes the complete Sable chunk/plot/shape audit.
     * No guessed emptiness, no forced chunk load, no cross-owner reuse. */
    public static ForcePropagation.NeighborReader propagationSnapshot(
            MinecraftServer server,String dimension) {
        if(server==null || dimension==null)return (from,x,y,z)->ForcePropagation.Probe.unknown();
        ServerLevel found=null;
        for(var level:server.getAllLevels())if(level.dimension().location().toString().equals(dimension)) {
            found=level;break;
        }
        if(found==null)return (from,x,y,z)->ForcePropagation.Probe.unknown();
        final ServerLevel level=found;
        final dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container;
        try {container=SubLevelContainer.getContainer(level);}
        catch(RuntimeException unavailable){return (from,x,y,z)->ForcePropagation.Probe.unknown();}
        if(container==null)return (from,x,y,z)->ForcePropagation.Probe.unknown();
        return new ForcePropagation.NeighborReader() {
            private final Map<String,PlotSnapshot> plots=new HashMap<>();
            private final Map<LocalCell,ForcePropagation.Probe> cells=new HashMap<>();
            @Override public ForcePropagation.Probe read(GameImpactCredit.Target from,int x,int y,int z) {
                if(from==null)return ForcePropagation.Probe.unknown();
                String owner=from.ownerKey();
                LocalCell key=new LocalCell(owner,x,y,z);
                ForcePropagation.Probe cached=cells.get(key);
                if(cached!=null)return cached;
                ForcePropagation.Probe value=inspect(owner,x,y,z);
                cells.put(key,value);
                return value;
            }
            private ForcePropagation.Probe inspect(String owner,int x,int y,int z) {
                try {
                    BlockTarget candidate;
                    if(owner.equals("WORLD:"+dimension)) {
                        candidate=new BlockTarget(Space.STATIC_WORLD,dimension,null,x,y,z,
                                true,"SABLE_EFFECT_CONSUMER_ALLOCATED_GRID");
                    } else if(owner.startsWith("PLOT:")) {
                        PlotSnapshot plot=plots.get(owner);
                        if(plot==null) {
                            UUID id=UUID.fromString(owner.substring(5));
                            var sub=container.getSubLevel(id);
                            if(!(sub instanceof ServerSubLevel actual) || actual.isRemoved()
                                    || actual.getLevel()!=level || !actual.getUniqueId().equals(id)
                                    || actual.getPlot()==null)return ForcePropagation.Probe.unknown();
                            plot=new PlotSnapshot(id,actual.getPlot().getCenterBlock());
                            plots.put(owner,plot);
                        }
                        candidate=new BlockTarget(Space.SABLE_PLOT,dimension,plot.id(),
                                Math.addExact(plot.center().getX(),x),
                                Math.addExact(plot.center().getY(),y),
                                Math.addExact(plot.center().getZ(),z),true,
                                "SABLE_EFFECT_CONSUMER_ALLOCATED_GRID");
                    } else return ForcePropagation.Probe.unknown();
                    Check checked=check(level,container,candidate,null);
                    if(checked.verified()) {
                        var target=checked.key();
                        return ForcePropagation.Probe.present(new GameImpactCredit.Target(owner,
                                target.x()+","+target.y()+","+target.z(),target.fingerprint()));
                    }
                    if(checked.status()==Status.BLOCK_UNAVAILABLE && (
                            checked.detail().equals("BLOCK_MISSING_OR_AIR")
                            || checked.detail().equals("WORLD_SHAPE_EMPTY")
                            || checked.detail().equals("PLOT_SHAPE_EMPTY")))
                        return ForcePropagation.Probe.empty();
                    return ForcePropagation.Probe.unknown();
                } catch(RuntimeException unsafe) {return ForcePropagation.Probe.unknown();}
            }
        };
    }
    public static ForcePropagation.Probe propagationNeighbor(MinecraftServer server,String dimension,
                                                              GameImpactCredit.Target from,
                                                              int x,int y,int z) {
        return propagationNeighbor(server,dimension,from,x,y,z,null);
    }
    private static ForcePropagation.Probe propagationNeighbor(MinecraftServer server,String dimension,
                                                              GameImpactCredit.Target from,
                                                              int x,int y,int z,
                                                              Map<String,BlockPos> centers) {
        if (server==null || dimension==null || from==null)return ForcePropagation.Probe.unknown();
        try {
            BlockPos cell=new BlockPos(x,y,z);
            if(from.ownerKey().startsWith("PLOT:")) {
                UUID id=UUID.fromString(from.ownerKey().substring(5));
                ServerLevel level=null;
                for(var candidate:server.getAllLevels())
                    if(candidate.dimension().location().toString().equals(dimension)) {level=candidate;break;}
                if(level==null)return ForcePropagation.Probe.unknown();
                var container=SubLevelContainer.getContainer(level);
                if(container==null)return ForcePropagation.Probe.unknown();
                var sub=container.getSubLevel(id);
                if(!(sub instanceof ServerSubLevel concrete) || concrete.isRemoved()
                        || concrete.getLevel()!=level || !concrete.getUniqueId().equals(id)
                        || concrete.getPlot()==null)
                    return ForcePropagation.Probe.unknown();
                BlockPos center=concrete.getPlot().getCenterBlock();
                if(centers!=null) {
                    BlockPos first=centers.putIfAbsent(from.ownerKey(),center);
                    if(first!=null && !first.equals(center))return ForcePropagation.Probe.unknown();
                }
                cell=new BlockPos(Math.addExact(center.getX(),x),Math.addExact(center.getY(),y),
                        Math.addExact(center.getZ(),z));
            }else if(!from.ownerKey().equals("WORLD:"+dimension))return ForcePropagation.Probe.unknown();
            Check checked=auditNative(server,dimension,from.ownerKey(),cell,null);
            if(checked.verified()) {
                var key=checked.key();
                return ForcePropagation.Probe.present(new GameImpactCredit.Target(from.ownerKey(),
                        key.x()+","+key.y()+","+key.z(),key.fingerprint()));
            }
            if(checked.status()==Status.BLOCK_UNAVAILABLE && (
                    checked.detail().equals("BLOCK_MISSING_OR_AIR") ||
                    checked.detail().equals("WORLD_SHAPE_EMPTY") ||
                    checked.detail().equals("PLOT_SHAPE_EMPTY")))
                return ForcePropagation.Probe.empty();
            return ForcePropagation.Probe.unknown();
        }catch(RuntimeException invalidOwnerOrPosition){return ForcePropagation.Probe.unknown();}
    }

    /** A source point must be on/in the actual collision shape of that owner
     * block, not merely inside some populated neighboring block. */
    private static boolean nearShape(VoxelShape shape,Vec point,BlockPos block) {
        if(shape==null || shape.isEmpty() || point==null || !point.finite())return false;
        return nearAabbs(shape.toAabbs(),point,block);
    }
    // Package-visible for geometry regression tests. No owner inference or credit here.
    static boolean nearAabbs(List<AABB> boxes,Vec point,BlockPos block) {
        if(boxes==null || boxes.isEmpty() || point==null || !point.finite())return false;
        double x=point.x()-block.getX(),y=point.y()-block.getY(),z=point.z()-block.getZ();
        final double eps2=(1.0/32)*(1.0/32);
        for(var box:boxes) {
            double dx=Math.max(0,Math.max(box.minX-x,x-box.maxX));
            double dy=Math.max(0,Math.max(box.minY-y,y-box.maxY));
            double dz=Math.max(0,Math.max(box.minZ-z,z-box.maxZ));
            if(dx*dx+dy*dy+dz*dz<=eps2)return true;
        }
        return false;
    }
    /** Converts allocated Plot-grid BlockPos to EmbeddedPlotLevelAccessor's centered coordinates. */
    public static BlockPos toEmbeddedPosition(BlockPos allocated, BlockPos plotCenter) {
        return new BlockPos(allocated.getX()-plotCenter.getX(),
                allocated.getY()-plotCenter.getY(),allocated.getZ()-plotCenter.getZ());
    }

    private static Check check(ServerLevel level, dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container,
                               BlockTarget proposed, String expectedBlockId) {
        return check(level,container,proposed,expectedBlockId,null);
    }
    private static Check check(ServerLevel level, dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container,
                               BlockTarget proposed, String expectedBlockId,Vec localContact) {
        return check(level,container,proposed,expectedBlockId,localContact,null);
    }
    private static Check check(ServerLevel level, dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container,
                                BlockTarget proposed, String expectedBlockId,Vec localContact,ShapeCapture capture) {
        if(proposed==null) return Check.failed(Status.NO_TARGET,"OTHER_NOT_PRESENT");
        if(!proposed.valid() || container==null || proposed.space()==Space.UNKNOWN)
            return Check.failed(Status.OWNER_UNRESOLVED, proposed.provenance());
        BlockPos pos=new BlockPos(proposed.x(),proposed.y(),proposed.z());
        ChunkPos chunk=new ChunkPos(pos);
        LevelPlot actualPlot;
        try { actualPlot=container.getPlot(chunk); }
        catch(RuntimeException ex) { return Check.failed(Status.OWNER_UNRESOLVED,"PLOT_LOOKUP_EXCEPTION"); }
        BlockState state;
        BlockPos keyPosition=pos; // WORLD is absolute; Plot key is embedded-accessor relative.
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
            keyPosition=relative;
            if(!actualPlot.getEmbeddedLevelAccessor().hasChunk(
                    chunk.x-centerChunk.x,chunk.z-centerChunk.z))
                return Check.failed(Status.BLOCK_UNAVAILABLE,"PLOT_CHUNK_UNLOADED");
            try {
                state=actualPlot.getEmbeddedLevelAccessor().getBlockState(relative);
                if(state!=null) {
                    var shape=state.getCollisionShape(actualPlot.getEmbeddedLevelAccessor(),relative);
                    if(shape.isEmpty())return Check.failed(Status.BLOCK_UNAVAILABLE,"PLOT_SHAPE_EMPTY");
                    if(capture!=null)capture.value=shape;
                    if(localContact!=null && !nearShape(shape,localContact,pos))
                        return Check.failed(Status.BLOCK_MISMATCH,"PLOT_SHAPE_MISS");
                }
            } catch(RuntimeException ex) {return Check.failed(Status.BLOCK_UNAVAILABLE,"PLOT_BLOCK_READ_FAILED");}
        } else {
            if(actualPlot!=null || container.inBounds(chunk))
                return Check.failed(Status.OWNER_UNRESOLVED,"PLOT_AREA_MUST_NOT_FALLBACK_TO_WORLD");
            if(!level.hasChunkAt(pos)) return Check.failed(Status.BLOCK_UNAVAILABLE,"WORLD_CHUNK_UNLOADED");
            try {
                state=level.getBlockState(pos);
                if(state!=null) {
                    var shape=state.getCollisionShape(level,pos);
                    if(shape.isEmpty())return Check.failed(Status.BLOCK_UNAVAILABLE,"WORLD_SHAPE_EMPTY");
                    if(capture!=null)capture.value=shape;
                    if(localContact!=null && !nearShape(shape,localContact,pos))
                        return Check.failed(Status.BLOCK_MISMATCH,"WORLD_SHAPE_MISS");
                }
            } catch(RuntimeException ex) {return Check.failed(Status.BLOCK_UNAVAILABLE,"WORLD_BLOCK_READ_FAILED");}
        }
        if(state==null || state.isAir())return Check.failed(Status.BLOCK_UNAVAILABLE,"BLOCK_MISSING_OR_AIR");
        String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if(expectedBlockId!=null && !id.equals(expectedBlockId))
            return Check.failed(Status.BLOCK_MISMATCH,"CALLBACK_BLOCK_ID_DIFFERENT_FROM_LIVE_STATE");
        return new Check(Status.VERIFIED,
                new TargetKey(proposed.dimension(),proposed.space(),proposed.stablePlotId(),
                        keyPosition.getX(),keyPosition.getY(),keyPosition.getZ(),id+"|"+state),"LIVE_OWNER_AND_BLOCK_VERIFIED",
                proposed.space()==Space.STATIC_WORLD && state.isCollisionShapeFullBlock(level,pos));
    }
}
