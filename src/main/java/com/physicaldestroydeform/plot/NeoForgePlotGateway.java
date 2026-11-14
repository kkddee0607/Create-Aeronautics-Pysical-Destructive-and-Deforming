package com.physicaldestroydeform.plot;

import com.mojang.authlib.GameProfile;
import com.physicaldestroydeform.state.SparseStateKey;
import com.physicaldestroydeform.world.WorldCommitCore;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.EmbeddedPlotLevelAccessor;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Actual Sable 2.0.6 Plot adapter: never transform into World permission/mutation. */
public final class NeoForgePlotGateway implements WorldCommitCore.WorldGateway {
    private static final Logger LOG=LoggerFactory.getLogger(NeoForgePlotGateway.class);
    private static final GameProfile ACTOR=new GameProfile(
            UUID.nameUUIDFromBytes("PDD:PLOT_COMMIT:0.5.1".getBytes(StandardCharsets.UTF_8)),
            "[PDD] Plot");
    private record Cell(ServerLevel level,ServerSubLevel sub,EmbeddedPlotLevelAccessor accessor,
                        BlockPos allocated,BlockPos local,SparseStateKey real) {}
    private final MinecraftServer server;
    private final PlotCommitService service;
    private final PlotOwnerFence fence=new PlotOwnerFence();
    private boolean inMutation;
    private SparseStateKey lastBreakKey;
    private boolean lastBreakWitness;
    public NeoForgePlotGateway(MinecraftServer server,PlotCommitService service){
        this.server=Objects.requireNonNull(server);this.service=Objects.requireNonNull(service);
    }
    @Override public boolean isServerThread(){return server.isSameThread();}
    public PlotOwnerFence fence(){return fence;}
    public boolean inMutation(){return inMutation;}
    private ServerLevel dimension(String dim){
        for(var level:server.getAllLevels())
            if(level.dimension().location().toString().equals(dim))return level;
        return null;
    }
    private static PlotOwnerFence.Stamp stamp(ServerSubLevel sub){
        BlockPos center=sub.getPlot().getCenterBlock();
        return new PlotOwnerFence.Stamp(sub.getRuntimeId(),center.getX(),center.getY(),center.getZ(),sub);
    }
    private Cell locate(SparseStateKey virtual,boolean register) {
        if(!isServerThread()||!PlotKeyCodec.isPlotStorage(virtual))return null;
        try {
            SparseStateKey real=PlotKeyCodec.real(virtual);
            var owner=PlotOwnerFence.Owner.of(real);
            if(fence.orphaned(owner)||service.isSplitParent(real.dimension(),owner.uuid()))return null;
            ServerLevel level=dimension(real.dimension());
            if(level==null)return null;
            var container=SubLevelContainer.getContainer(level);
            if(container==null)return null;
            var raw=container.getSubLevel(owner.uuid());
            if(!(raw instanceof ServerSubLevel sub)||sub.isRemoved()||sub.getLevel()!=level
                    ||!sub.getUniqueId().equals(owner.uuid())||sub.getPlot()==null)return null;
            var plot=sub.getPlot();
            UUID splitParent=sub.getSplitFromSubLevel();
            if(splitParent!=null){
                // Child UUID is a NEW owner; only its parent loses old sparse
                // damage. A new child may receive fresh impact after durable
                // parent quarantine, never inherited parent Q or damage.
                service.quarantineParent(real.dimension(),splitParent);
                if(service.faulted())return null;
            }
            var current=stamp(sub);
            if(register){
                if(!fence.bind(owner,current,false))return null;
            }else if(!fence.verify(owner,current,false))return null;
            // Native evidence is in the Plot's allocated grid. Accessor takes
            // center-relative owner lattice; subtract exactly once.
            BlockPos center=plot.getCenterBlock();
            BlockPos allocated=new BlockPos(Math.addExact(real.x(),center.getX()),
                    Math.addExact(real.y(),center.getY()),Math.addExact(real.z(),center.getZ()));
            ChunkPos chunk=new ChunkPos(allocated);
            if(container.getPlot(chunk)!=plot)return null;
            var accessor=plot.getEmbeddedLevelAccessor();
            if(accessor==null||!accessor.hasChunk(chunk.x-plot.getCenterChunk().x,
                    chunk.z-plot.getCenterChunk().z))return null;
            // No getChunk(); no implicit load, no World-space mutation.
            return new Cell(level,sub,accessor,allocated,
                    new BlockPos(real.x(),real.y(),real.z()),real);
        }catch(RuntimeException|LinkageError invalid){return null;}
    }
    /** Bind only on a newly issued Q with a live matching owner; no speculative recovery bind. */
    public boolean bindOnOffer(SparseStateKey original){
        if(!"PLOT".equals(original.ownerSpace()))return false;
        return locate(PlotKeyCodec.storage(original),true)!=null;
    }
    /** Report Sable's actual split lineage. Never guess old/new block correspondence. */
    public void detectLifecycle(){
        if(!isServerThread())throw new IllegalStateException("server thread required");
        for(ServerLevel level:server.getAllLevels()){
            var container=SubLevelContainer.getContainer(level);
            if(container==null)continue;
            String dim=level.dimension().location().toString();
            for(var raw:container.getAllSubLevels()) {
                if(!(raw instanceof ServerSubLevel sub)||sub.isRemoved())continue;
                UUID parent=sub.getSplitFromSubLevel();
                if(parent!=null){
                    fence.orphanAncestors(dim,parent);
                    // An unobserved parent may have saved state from a previous session.
                    service.quarantineParent(dim,parent);
                }
            }
        }
        for(var owner:fence.boundSnapshot()) {
            ServerLevel level=dimension(owner.dimension());
            if(level==null)continue;
            var container=SubLevelContainer.getContainer(level);
            if(container==null)continue;
            var raw=container.getSubLevel(owner.uuid());
            if(raw instanceof ServerSubLevel sub && !sub.isRemoved() && sub.getPlot()!=null) {
                if(!fence.verify(owner,stamp(sub),sub.getSplitFromSubLevel()!=null)){
                    LOG.warn("[PDD-PLOT-LIFECYCLE] sessionId={} phase=OWNER_EPOCH_MISMATCH dim={} owner={} " +
                            "generation={} runtimeBodyId={} splitFrom={} action=ORPHAN_QUARANTINE",
                            PlotPhysicsProbe.sessionId(),owner.dimension(),owner.uuid(),owner.generation(),sub.getRuntimeId(),sub.getSplitFromSubLevel());
                    fence.orphan(owner);
                }
            }
            // Missing owners may merely be unloaded. Never infer a split from absence.
        }
    }
    @Override public WorldCommitCore.Observation readIfLoaded(SparseStateKey virtual){
        Cell cell=locate(virtual,false);
        if(cell==null)return null;
        try {
            BlockState state=cell.accessor().getBlockState(cell.local());
            if(state==null)return null;
            String fingerprint=BuiltInRegistries.BLOCK.getKey(state.getBlock())+"|"+state;
            boolean be=!state.isAir() && (state.hasBlockEntity()
                    ||cell.accessor().getBlockEntity(cell.local())!=null);
            boolean linked=state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                    ||state.getBlock() instanceof BedBlock ||state.getBlock() instanceof DoorBlock;
            boolean immune=state.getDestroySpeed(cell.accessor(),cell.local())<0;
            return new WorldCommitCore.Observation(virtual.dimension(),fingerprint,virtual.ownerGeneration(),
                    service.incarnation(new SparseStateKey(cell.real().dimension(),"PLOT",
                            cell.real().ownerId(),cell.real().ownerGeneration(),
                            cell.real().x(),cell.real().y(),cell.real().z(),fingerprint),
                            cell.sub().getPlot().getCenterBlock()),
                    true,state.isAir(),be,linked,immune,false);
        }catch(RuntimeException|LinkageError unsafe){return null;}
    }
    @Override public WorldCommitCore.Permission canDamage(SparseStateKey virtual,WorldCommitCore.Observation expected){
        if(!isServerThread())return WorldCommitCore.Permission.UNKNOWN;
        Cell cell=locate(virtual,false);
        if(cell==null)return WorldCommitCore.Permission.UNKNOWN;
        var observed=readIfLoaded(virtual);
        if(observed==null||!expected.equals(observed)||observed.isAir()||observed.hasBlockEntity()
                ||observed.unsafeMultiblock()||observed.unbreakable())return WorldCommitCore.Permission.DENY;
        try {
            var player=FakePlayerFactory.get(cell.level(),ACTOR);
            return cell.level().mayInteract(player,cell.allocated()) && player.mayBuild()
                    ?WorldCommitCore.Permission.ALLOW:WorldCommitCore.Permission.DENY;
        }catch(RuntimeException|LinkageError error){return WorldCommitCore.Permission.UNKNOWN;}
    }
    @Override public WorldCommitCore.Permission canBreak(SparseStateKey virtual,WorldCommitCore.Observation expected){
        if(canDamage(virtual,expected)!=WorldCommitCore.Permission.ALLOW)
            return WorldCommitCore.Permission.DENY;
        Cell cell=locate(virtual,false);
        if(cell==null)return WorldCommitCore.Permission.UNKNOWN;
        try {
            var player=FakePlayerFactory.get(cell.level(),ACTOR);
            // Claims see the same allocated chunk location that contains the Plot block.
            BlockEvent.BreakEvent veto=new BlockEvent.BreakEvent(cell.level(),cell.allocated(),
                    cell.level().getBlockState(cell.allocated()),player);
            NeoForge.EVENT_BUS.post(veto);
            return veto.isCanceled()?WorldCommitCore.Permission.DENY:WorldCommitCore.Permission.ALLOW;
        }catch(RuntimeException|LinkageError error){return WorldCommitCore.Permission.UNKNOWN;}
    }
    @Override public boolean breakVanillaOnce(SparseStateKey virtual){
        lastBreakKey=null;
        lastBreakWitness=false;
        if(!isServerThread())return false;
        Cell cell=locate(virtual,false);
        if(cell==null||inMutation)return false;
        var observation=readIfLoaded(virtual);
        if(observation==null||observation.isAir()||observation.hasBlockEntity()
                ||observation.unsafeMultiblock()||observation.unbreakable())return false;
        // The supported embedded accessor delegates to the allocated LevelChunk;
        // Sable LevelChunkMixin handles its normal block-change notification.
        // Never call force rebuild, removeSubLevel, or fabricate split/physics state.
        String dim=cell.real().dimension();
        String target=cell.real().ownerId()+":"+cell.local();
        PlotPhysicsProbe.beforeBreak(cell.sub(),dim,target);
        BlockState expected=cell.accessor().getBlockState(cell.local());
        if(expected==null || expected.isAir()
                || !(BuiltInRegistries.BLOCK.getKey(expected.getBlock())+"|"+expected)
                    .equals(observation.fingerprint()))return false;
        PlotBreakMutationWitness.begin(cell.level(),cell.allocated(),expected);
        lastBreakKey=virtual;
        inMutation=true;service.beginOwnBreak(virtual);
        try {
            boolean result=cell.accessor().destroyBlock(cell.local(),true,null,512);
            PlotPhysicsProbe.afterBreak(cell.sub(),dim,target,result);
            return result;
        } finally {
            lastBreakWitness=PlotBreakMutationWitness.end();
            service.endOwnBreak();inMutation=false;
        }
    }
    @Override public boolean witnessedBreakToAir(SparseStateKey key){
        return isServerThread() && lastBreakWitness && key.equals(lastBreakKey);
    }
    @Override public void onBreakException(SparseStateKey virtual,RuntimeException failure){
        LOG.error("[PDD-PLOT-BREAK] phase=EXCEPTION key={} result=PARTIAL_QUARANTINED " +
                "mutation=UNCERTAIN action=NO_RETRY",virtual,failure);
    }
    @Override public void onBreakReadback(SparseStateKey virtual,boolean apiResult,
            WorldCommitCore.Observation reread,WorldCommitCore.BreakReadback status){
        if(status==WorldCommitCore.BreakReadback.CONFIRMED_AIR)return;
        if(status==WorldCommitCore.BreakReadback.CONFIRMED_AIR_MUTATION_WITNESS){
            LOG.info("[PDD-PLOT-WITNESS] sessionId={} phase=CONFIRMED_BLOCK_TO_AIR " +
                    "key={} result=APPLIED_BREAK source=LEVEL_CHUNK_RETURN " +
                    "nativeMassCollider=UNVERIFIED",
                    PlotPhysicsProbe.sessionId(),virtual);
            return;
        }
        String explanation=reread==null?readbackMissingReason(virtual):
                (reread.loaded()?"PRESENT_OR_BE":"UNLOADED");
        SparseStateKey real;
        try{real=PlotKeyCodec.real(virtual);}catch(RuntimeException malformed){real=virtual;}
        LOG.warn("[PDD-PLOT-REREAD] sessionId={} phase=AFTER_BREAK result={} reason={} apiResult={} " +
                "owner={} generation={} dim={} local={},{},{} " +
                "readLoaded={} readAir={} readBE={} nativeMassCollider=UNVERIFIED " +
                "receiptPolicy=QUARANTINE_NO_RETRY",
                PlotPhysicsProbe.sessionId(),status,explanation,apiResult,real.ownerId(),real.ownerGeneration(),real.dimension(),
                real.x(),real.y(),real.z(),reread!=null&&reread.loaded(),
                reread!=null&&reread.isAir(),reread!=null&&reread.hasBlockEntity());
    }
    /** Explain a failed read without changing the fence, loading chunks or rebuilding Sable. */
    private String readbackMissingReason(SparseStateKey virtual){
        if(!isServerThread())return "NOT_SERVER_THREAD";
        try {
            SparseStateKey real=PlotKeyCodec.real(virtual);
            var owner=PlotOwnerFence.Owner.of(real);
            if(fence.orphaned(owner))return "OWNER_ORPHANED";
            if(service.isSplitParent(real.dimension(),owner.uuid()))return "SPLIT_PARENT";
            ServerLevel level=dimension(real.dimension());
            if(level==null)return "DIMENSION_UNAVAILABLE";
            var container=SubLevelContainer.getContainer(level);
            if(container==null)return "CONTAINER_UNAVAILABLE";
            var raw=container.getSubLevel(owner.uuid());
            if(!(raw instanceof ServerSubLevel sub))return "SUBLEVEL_UNAVAILABLE";
            if(sub.isRemoved())return "SUBLEVEL_REMOVED";
            if(sub.getPlot()==null)return "PLOT_UNAVAILABLE";
            if(sub.getLevel()!=level||!sub.getUniqueId().equals(owner.uuid()))return "OWNER_MISMATCH";
            if(!fence.verify(owner,stamp(sub),false))return "OWNER_EPOCH_MISMATCH";
            var plot=sub.getPlot();
            BlockPos center=plot.getCenterBlock();
            BlockPos allocated=new BlockPos(Math.addExact(real.x(),center.getX()),
                    Math.addExact(real.y(),center.getY()),Math.addExact(real.z(),center.getZ()));
            ChunkPos chunk=new ChunkPos(allocated);
            if(container.getPlot(chunk)!=plot)return "CHUNK_PLOT_MISMATCH";
            var accessor=plot.getEmbeddedLevelAccessor();
            if(accessor==null)return "EMBEDDED_ACCESSOR_MISSING";
            if(!accessor.hasChunk(chunk.x-plot.getCenterChunk().x,
                    chunk.z-plot.getCenterChunk().z))return "EMBEDDED_CHUNK_UNLOADED";
            return "BLOCK_READ_FAILED";
        }catch(RuntimeException|LinkageError failure){return "READ_EXCEPTION_"+failure.getClass().getSimpleName();}
    }

}
