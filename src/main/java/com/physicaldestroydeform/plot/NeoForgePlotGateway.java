package com.physicaldestroydeform.plot;

import com.mojang.authlib.GameProfile;
import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.collision.FrameMath;
import com.physicaldestroydeform.deform.MovePlan;
import com.physicaldestroydeform.deform.MoveTransactionExecutor;
import com.physicaldestroydeform.deform.MoveTransactionJournal;
import com.physicaldestroydeform.deform.MoveSafety;
import com.physicaldestroydeform.material.CopycatMovePayload;
import com.physicaldestroydeform.material.MaterialRegistry;
import com.physicaldestroydeform.material.TargetMaterials;
import com.physicaldestroydeform.state.SparseStateKey;
import com.physicaldestroydeform.mixin.SableHeatMapSafetyAccessor;
import com.physicaldestroydeform.world.WorldCommitCore;
import com.physicaldestroydeform.world.WorldReceiptJournal;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.plot.EmbeddedPlotLevelAccessor;
import java.nio.charset.StandardCharsets;
import java.lang.ref.WeakReference;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Actual Sable 2.0.6 Plot adapter: never transform into World permission/mutation. */
public final class NeoForgePlotGateway implements WorldCommitCore.WorldGateway,MoveTransactionExecutor.Gateway {
    private static final Logger LOG=LoggerFactory.getLogger(NeoForgePlotGateway.class);
    private static final GameProfile ACTOR=new GameProfile(
            UUID.nameUUIDFromBytes("PDD:PLOT_COMMIT:0.5.1".getBytes(StandardCharsets.UTF_8)),
            "[PDD] Plot");
    private record Cell(ServerLevel level,ServerSubLevel sub,EmbeddedPlotLevelAccessor accessor,
                        BlockPos allocated,BlockPos local,SparseStateKey real) {}
    private final MinecraftServer server;
    private final PlotCommitService service;
    private final PlotOwnerFence fence=new PlotOwnerFence();
    private record LifecycleId(String dimension,UUID uuid) {}
    private record Reload(WeakReference<SubLevelContainer> container,WeakReference<ServerSubLevel> sub) {
        Reload(SubLevelContainer container,ServerSubLevel sub){this(new WeakReference<>(container),new WeakReference<>(sub));}
    }
    private final Set<SubLevelContainer> observedContainers=Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<PlotOwnerFence.Owner,WeakReference<SubLevelContainer>> suspendedContainers=new HashMap<>();
    private final Map<PlotOwnerFence.Owner,Reload> reloads=new HashMap<>();
    private final Set<LifecycleId> removedOwners=new HashSet<>();
    private final Map<PlotOwnerFence.Owner,Long> changedTicks=new HashMap<>();
    private boolean observing=true;
    private boolean lifecycleDirty=true;
    public record LifecycleBinding(long generation,long revision,boolean active,boolean orphaned,int runtimeId,long changedTick) {}
    /** Sable has no observer removal API in 2.0.6; use weak ownership and stop guard. */
    private static final class LifecycleObserver implements SubLevelObserver {
        private final WeakReference<NeoForgePlotGateway> target;
        private final SubLevelContainer container;
        private final String dimension;
        LifecycleObserver(NeoForgePlotGateway target,SubLevelContainer container,String dimension){
            this.target=new WeakReference<>(target);this.container=container;this.dimension=dimension;
        }
        @Override public void onSubLevelAdded(SubLevel sub){
            var current=target.get();
            if(current!=null)try {current.observedAdd(container,dimension,sub);}
            catch(RuntimeException|LinkageError ignored){ /* never disrupt Sable allocation */ }
        }
        @Override public void onSubLevelRemoved(SubLevel sub,SubLevelRemovalReason reason){
            var current=target.get();
            if(current!=null)try {current.observedRemove(container,dimension,sub,reason);}
            catch(RuntimeException|LinkageError ignored){ /* never disrupt Sable removal */ }
        }
    }
    private boolean inMutation;
    private SparseStateKey lastBreakKey;
    private boolean lastBreakWitness;
    /** At most one structural break attempt per owner over the configured tick gap. */
    private final Map<PlotOwnerFence.Owner,PlotBreakPacing.Attempt> lastBreakAttempt=new HashMap<>();
    private final int breakSpacingTicks=PlotBreakPacing.spacing(
            Integer.getInteger("pdd.plotBreakSpacingTicks",1));
    private long heatMapHolds,spacingHolds,tickWitnessHolds;
    private static final int MOVE_FLAGS=Block.UPDATE_ALL|Block.UPDATE_MOVE_BY_PISTON;
    private static final String UNOBSERVED_DEST="PDD:MOVE_DESTINATION_UNAVAILABLE";
    private static final boolean TRACE_MOVES=Boolean.parseBoolean(System.getProperty("pdd.deform.trace","true"));
    private record MoveWait(long tick,MoveTransactionExecutor.Readback expected,double expectedMass) {}
    private final Map<PlotOwnerFence.Owner,String> ownerMoves=new HashMap<>();
    private final Map<String,MoveWait> moveWaits=new HashMap<>();
    private SparseStateKey ownMoveWrite;
    private BlockState ownMoveBefore,ownMoveAfter;
    private int ownMoveWitnesses;
    public NeoForgePlotGateway(MinecraftServer server,PlotCommitService service){
        this.server=Objects.requireNonNull(server);this.service=Objects.requireNonNull(service);
    }
    @Override public boolean isServerThread(){return server.isSameThread();}
    @Override public boolean serverThread(){return isServerThread();}
    public MoveTransactionExecutor.Gateway movementGateway(){return this;}
    /** Only the currently executing normal setter, never a blanket owner bypass. */
    public boolean ownsMoveMutation(SparseStateKey key,BlockState before,BlockState after){
        if(ownMoveWrite==null||!sameCell(ownMoveWrite,key)||!ownMoveBefore.equals(before)||!ownMoveAfter.equals(after))return false;
        ownMoveWitnesses++;return true;
    }
    private static boolean sameCell(SparseStateKey a,SparseStateKey b){
        return a.dimension().equals(b.dimension())&&a.ownerId().equals(b.ownerId())
                &&a.ownerGeneration()==b.ownerGeneration()&&a.x()==b.x()&&a.y()==b.y()&&a.z()==b.z();
    }
    @Override public boolean materialMatches(WorldCommitCore.Intent intent){
        var snapshot=com.physicaldestroydeform.material.MaterialRegistry.snapshot();
        if(snapshot.generation()!=intent.materialGeneration()||locate(intent.key(),false)==null)return false;
        var material=com.physicaldestroydeform.material.TargetMaterials.read(server,intent.key(),snapshot);
        return material!=null&&intent.material().equals(material.material())&&intent.materialRevision().equals(material.revision());
    }
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
    public LifecycleBinding lifecycleBinding(String dimension,UUID uuid){
        var owner=fence.owner(dimension,uuid);
        if(owner==null)return null;
        var current=fence.activeStamp(owner);
        return new LifecycleBinding(owner.generation(),fence.lifecycleRevision(owner),
                current!=null&&!fence.orphaned(owner),fence.orphaned(owner),current==null?-1:current.runtimeId(),
                changedTicks.getOrDefault(owner,-1L));
    }
    public boolean lifecycleDirty(){return lifecycleDirty;}
    public void stopLifecycle(){
        observing=false;observedContainers.clear();reloads.clear();suspendedContainers.clear();
        removedOwners.clear();changedTicks.clear();
    }
    private void observedRemove(SubLevelContainer container,String dim,SubLevel raw,SubLevelRemovalReason reason){
        if(!observing||!isServerThread()||!(raw instanceof ServerSubLevel sub))return;
        // Only identity bookkeeping in Sable's callback. No reads of blocks, WAL writes or world mutation.
        var owner=fence.owner(dim,sub.getUniqueId());
        lifecycleDirty=true;
        try {
            if(reason!=SubLevelRemovalReason.UNLOADED){
                removedOwners.add(new LifecycleId(dim,sub.getUniqueId()));
                if(owner!=null)fence.orphan(owner);
            }else if(owner!=null&&fence.suspend(owner,stamp(sub))){
                suspendedContainers.put(owner,new WeakReference<>(container));
            }
            if(owner!=null){reloads.remove(owner);changedTicks.put(owner,(long)server.getTickCount());}
        }catch(RuntimeException|LinkageError badProof){if(owner!=null)fence.orphan(owner);}
    }
    private void observedAdd(SubLevelContainer container,String dim,SubLevel raw){
        if(!observing||!isServerThread()||!(raw instanceof ServerSubLevel sub))return;
        lifecycleDirty=true;
        var owner=fence.owner(dim,sub.getUniqueId());
        if(owner==null||fence.orphaned(owner))return;
        try {
            if(fence.suspended(owner)){
                var suspendedContainer=suspendedContainers.get(owner);
                if(suspendedContainer==null||suspendedContainer.get()!=container||reloads.putIfAbsent(owner,new Reload(container,sub))!=null)
                    fence.orphan(owner);
            }else if(!fence.verify(owner,stamp(sub),false))fence.orphan(owner);
        }catch(RuntimeException|LinkageError badProof){fence.orphan(owner);}
        if(fence.orphaned(owner))changedTicks.put(owner,(long)server.getTickCount());
    }
    private Cell locate(SparseStateKey virtual,boolean register) {
        return locate(virtual,register,null);
    }
    private Cell locate(SparseStateKey virtual,boolean register,String expectedIncarnation) {
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
            if(expectedIncarnation!=null && (splitParent!=null
                    || !expectedIncarnation.equals(service.incarnation(real,plot.getCenterBlock()))))return null;
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
    /** Durable owner-local identity is checked again by the core before any mutation.
     * No runtime ID, object reference, or old HeatMap witness is restored. */
    @Override public WorldCommitCore.Observation readRecoveredIfLoaded(WorldCommitCore.Intent intent){
        Cell cell=locate(intent.key(),true,intent.expectedIncarnation());
        if(cell==null)return null;
        return readIfLoaded(intent.key());
    }
    boolean bindRecoveredStressRoot(SparseStateKey key,String expectedIncarnation){
        return locate(key,true,Objects.requireNonNull(expectedIncarnation))!=null;
    }
    @Override public String rejectRecoveredReason(WorldCommitCore.Intent intent){
        if(service.isMoveQuarantined(intent.key()))return "RECOVERY_MOVE_CELL_QUARANTINED";
        var owner=PlotOwnerFence.Owner.of(PlotKeyCodec.real(intent.key()));
        return fence.orphaned(owner)||service.isSplitParent(owner.dimension(),owner.uuid())
                ?"RECOVERY_PLOT_ORPHANED":null;
    }
    /** Report Sable's actual split lineage. Never guess old/new block correspondence. */
    public void detectLifecycle(){
        if(!isServerThread())throw new IllegalStateException("server thread required");
        lifecycleDirty=false;
        for(var removed:removedOwners)service.quarantineRemoved(removed.dimension(),removed.uuid());
        removedOwners.clear();
        for(ServerLevel level:server.getAllLevels()){
            var container=SubLevelContainer.getContainer(level);
            if(container==null)continue;
            String dim=level.dimension().location().toString();
            if(observedContainers.add(container))container.addObserver(new LifecycleObserver(this,container,dim));
            for(var raw:container.getAllSubLevels()) {
                if(!(raw instanceof ServerSubLevel sub)||sub.isRemoved())continue;
                UUID parent=sub.getSplitFromSubLevel();
                if(parent!=null){
                    var parentOwner=fence.owner(dim,parent);
                    if(parentOwner!=null&&!fence.orphaned(parentOwner))changedTicks.put(parentOwner,(long)server.getTickCount());
                    fence.orphanAncestors(dim,parent);
                    // An unobserved parent may have saved state from a previous session.
                    service.quarantineParent(dim,parent);
                }
                var owner=fence.owner(dim,sub.getUniqueId());
                if(owner==null||fence.orphaned(owner)||sub.getPlot()==null)continue;
                if(fence.suspended(owner)){
                    var candidate=reloads.remove(owner);
                    // Sable add occurs before the plot finishes loading. Rebind only at post-tick,
                    // after live identity and lineage are rechecked. Same UUID alone is insufficient.
                    boolean proof=candidate!=null&&candidate.container().get()==container&&candidate.sub().get()==sub
                            &&container.getSubLevel(owner.uuid())==sub&&sub.getLevel()==level
                            &&!service.isSplitParent(dim,owner.uuid())&&parent==null;
                    if(proof&&fence.resume(owner,stamp(sub),false)){
                        suspendedContainers.remove(owner);
                        changedTicks.put(owner,(long)server.getTickCount());
                        LOG.info("[PDD-PLOT-LIFECYCLE] phase=REBOUND dim={} owner={} generation={} runtimeBodyId={} proof=UNLOAD_ADD_SAME_CENTER",dim,owner.uuid(),owner.generation(),sub.getRuntimeId());
                        continue;
                    }
                }
                // A newly bound split CHILD is independent; only its parent is quarantined above.
                if(!fence.verify(owner,stamp(sub),false)){
                    LOG.warn("[PDD-PLOT-LIFECYCLE] sessionId={} phase=OWNER_EPOCH_MISMATCH dim={} owner={} " +
                            "generation={} runtimeBodyId={} splitFrom={} action=ORPHAN_QUARANTINE",
                            PlotPhysicsProbe.sessionId(),owner.dimension(),owner.uuid(),owner.generation(),sub.getRuntimeId(),sub.getSplitFromSubLevel());
                    fence.orphan(owner);
                    changedTicks.put(owner,(long)server.getTickCount());
                }
            }
        }
        // Release dead object references promptly; quarantined UUID tombstones remain.
        reloads.keySet().removeIf(fence::orphaned);
        suspendedContainers.keySet().removeIf(fence::orphaned);
    }
    /** Sable 2.0.6 HeatMap split can remain unfinished for multiple server ticks.
     * Never submit another PDD Plot break while it has pending work. Sable's
     * own split/assembly/collider code is not changed by PDD.
     */
    @Override public String deferBreakReason(SparseStateKey virtual) {
        if(!isServerThread())return "PLOT_NOT_SERVER_THREAD";
        Cell cell=locate(virtual,false);
        if(cell==null)return "PLOT_OWNER_NOT_READY";
        if(ownerMoves.containsKey(PlotOwnerFence.Owner.of(cell.real())))return "PLOT_MOVE_POSTSTATE_PENDING";
        return deferBreakReason(cell,false);
    }
    private static PlotHeatMapReadCache.Pending readPendingHeatMap(SableHeatMapSafetyAccessor s){
        boolean initialized=s.pdd$initialized(),complete=s.pdd$splitComplete();
        int flood=s.pdd$floodfill().size(),removed=s.pdd$removed().size(),
                starts=s.pdd$newStarts().size(),splitCells=s.pdd$subLevelSplits().size();
        return !initialized||!complete||flood>0||removed>0||starts>0||splitCells>0
                ?new PlotHeatMapReadCache.Pending(initialized,complete,flood,removed,starts,splitCells):null;
    }
    private String deferBreakReason(Cell cell,boolean forceLive) {
        try {
            if(cell.sub().isRemoved())return "PLOT_OWNER_NOT_READY";
            var heat=cell.sub().getHeatMapManager();
            if(!(heat instanceof SableHeatMapSafetyAccessor s)) {
                if(++heatMapHolds==1 || heatMapHolds%256==0)
                    LOG.warn("[PDD-PLOT-SAFE] phase=HEATMAP_UNVERIFIED owner={} " +
                                    "runtimeBodyId={} holdCount={} action=DEFER_BEFORE_PREPARED",
                            cell.sub().getUniqueId(),cell.sub().getRuntimeId(),heatMapHolds);
                return "SABLE_HEATMAP_UNVERIFIED";
            }
            long tick=server.getTickCount();
            var pending=(Object)heat instanceof PlotHeatMapReadCache.Witness witness
                    ?witness.pdd$heatMapReadCache().pending(tick,forceLive,()->readPendingHeatMap(s))
                    :readPendingHeatMap(s);
            if(pending!=null) {
                if(++heatMapHolds==1 || heatMapHolds%256==0)
                    LOG.warn("[PDD-PLOT-SAFE] phase=WAIT_SABLE_HEATMAP " +
                                    "owner={} generation={} runtimeBodyId={} initialized={} " +
                                    "splitComplete={} floodfill={} removed={} newStarts={} splitCells={} " +
                                    "holdCount={} action=DEFER_BEFORE_PREPARED",
                            cell.sub().getUniqueId(),cell.real().ownerGeneration(),cell.sub().getRuntimeId(),
                            pending.initialized(),pending.complete(),pending.flood(),pending.removed(),pending.starts(),pending.splitCells(),heatMapHolds);
                return "SABLE_HEATMAP_PENDING_SPLIT";
            }
            var owner=PlotOwnerFence.Owner.of(cell.real());
            long completed=(Object)cell.sub() instanceof PlotHeatMapTickWitness witness
                    ?witness.pdd$completedHeatMapServerTick():-1;
            String hold=PlotBreakPacing.holdReason(tick,completed,lastBreakAttempt.get(owner),breakSpacingTicks);
            if("PLOT_BREAK_SPACING".equals(hold)) spacingHolds++;
            else if(hold!=null) {
                if(++tickWitnessHolds==1 || tickWitnessHolds%256==0)
                    LOG.warn("[PDD-PLOT-SAFE] phase=WAIT_SABLE_TICK owner={} generation={} " +
                                    "serverTick={} completedHeatMapTick={} holdCount={} action=DEFER_BEFORE_PREPARED",
                            owner.uuid(),owner.generation(),tick,completed,tickWitnessHolds);
            }
            return hold;
        } catch(RuntimeException|LinkageError unsafe) {
            heatMapHolds++;
            // A failed observation cannot grant a destructive mutation.
            return "SABLE_HEATMAP_UNVERIFIED";
        }
    }
    void pruneSafetyHistory(long tick){
        if(tick%100==0)lastBreakAttempt.entrySet().removeIf(e->tick-e.getValue().tick()>1200);
    }
    long heatMapHolds(){return heatMapHolds;}
    long spacingHolds(){return spacingHolds;}
    long tickWitnessHolds(){return tickWitnessHolds;}
    int breakSpacingTicks(){return breakSpacingTicks;}
    @Override public WorldCommitCore.Observation readIfLoaded(SparseStateKey virtual){
        Cell cell=locate(virtual,false);
        if(cell==null)return null;
        try {
            BlockState state=cell.accessor().getBlockState(cell.local());
            if(state==null)return null;
            String fingerprint=BuiltInRegistries.BLOCK.getKey(state.getBlock())+"|"+state;
            boolean be=!state.isAir() && (state.hasBlockEntity()
                    ||cell.accessor().getBlockEntity(cell.local())!=null);
            if(be&&com.physicaldestroydeform.material.CopycatMaterial.isCopycat(fingerprint)){
                var resolved=com.physicaldestroydeform.material.CopycatMaterial.capture(com.physicaldestroydeform.material.MaterialRegistry.snapshot(),cell.accessor(),cell.local(),state);
                if(resolved!=null&&resolved.safeDecorativeBE())be=false;
            }
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
        if(!isServerThread()||service.isMoveQuarantined(virtual))return WorldCommitCore.Permission.UNKNOWN;
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
    private static SparseStateKey at(SparseStateKey key,BlockPos pos,String fingerprint){
        return new SparseStateKey(key.dimension(),key.ownerSpace(),key.ownerId(),key.ownerGeneration(),
                pos.getX(),pos.getY(),pos.getZ(),fingerprint);
    }
    private static BlockState state(Cell cell){return cell.accessor().getBlockState(cell.local());}
    private static String fp(BlockState state){return BuiltInRegistries.BLOCK.getKey(state.getBlock())+"|"+state;}
    private static boolean hasBE(Cell c){return state(c).hasBlockEntity()||c.accessor().getBlockEntity(c.local())!=null;}
    private static boolean empty(Cell c){return state(c).isAir()&&state(c).getFluidState().isEmpty()&&!hasBE(c);}
    private static boolean clearPath(Cell c){return MoveSafety.clearPathCell(c.level(),c.allocated(),state(c));}
    private static boolean sameOwner(Cell a,Cell b){return a!=null&&b!=null&&a.sub()==b.sub()&&a.level()==b.level();}
    private static String payload(Cell c){return CopycatMovePayload.capture(c.accessor(),c.local(),state(c),c.level().registryAccess());}
    private static String payload(MoveTransactionJournal.BeforeImage b){byte[] data=b.bePayload();return data==null?null:new String(data,StandardCharsets.UTF_8);}
    private static boolean payloadMatches(Cell c,MoveTransactionJournal.BeforeImage b){
        String expected=payload(b);
        return expected==null?!hasBE(c):CopycatMovePayload.matches(c.accessor(),c.local(),state(c),c.level().registryAccess(),expected);
    }
    /** The direct-damage receipt is already durable. Do not create a destination
     * from a World transform: use the frozen signed owner-lattice steps. */
    public MoveTransactionJournal.BeforeImage planMove(WorldCommitCore.Intent intent,MovePlan plan,
            WorldReceiptJournal.State current,DamageModel.Settings settings,long gameTick){
        if(!isServerThread()||plan.status()!=MovePlan.Status.CANDIDATE_ONLY||current==null)return null;
        try{
            var source=intent.key();
            if(!source.equals(plan.source())&&!PlotKeyCodec.real(source).equals(plan.source()))return null;
            Cell a=locate(source,false);
            if(a==null||!source.fingerprint().equals(fp(state(a)))||!current.key().equals(source)
                    ||!current.incarnation().equals(intent.expectedIncarnation()))return null;
            BlockPos destination=a.local();
            for(var step:plan.steps())destination=new BlockPos(Math.addExact(destination.getX(),step.dx()),
                    Math.addExact(destination.getY(),step.dy()),Math.addExact(destination.getZ(),step.dz()));
            Cell b=locate(at(source,destination,"PDD:MOVE_PROBE"),false);
            if(b!=null&&!sameOwner(a,b))return null;
            var before=at(source,destination,b==null?UNOBSERVED_DEST:fp(state(b)));
            var after=at(source,destination,source.fingerprint());
            long ar=service.moveRevision(source),br=service.moveRevision(before);
            var center=a.sub().getPlot().getCenterBlock();
            String token=PlotIncarnation.token(PlotKeyCodec.real(after),center.getX(),center.getY(),center.getZ(),Math.addExact(br,1));
            var moved=new WorldReceiptJournal.State(after,token,current.damage(),current.remainder(),current.materialGeneration(),server.getTickCount(),
                    Math.min(255,current.deformation()+1));
            String be=hasBE(a)?payload(a):null;
            byte[] bytes=be==null?null:be.getBytes(StandardCharsets.UTF_8);
            if(bytes!=null&&bytes.length>8192)bytes=null; // oversized unsupported BE is vetoed below
            double candidate=Math.max(0,intent.deformationEvidence().blockedCandidateQ()),applied=Math.min(candidate,plan.reservedQ());
            return new MoveTransactionJournal.BeforeImage(intent.applyId()+"/move",intent.applyId(),source,before,current,moved,
                    ar,br,gameTick,gameTick>Long.MAX_VALUE-WorldCommitCore.MAX_DEFER_TICKS?Long.MAX_VALUE:gameTick+WorldCommitCore.MAX_DEFER_TICKS,plan.directionEpoch(),plan.reservedQ(),
                    intent.material(),intent.materialRevision(),bytes,candidate,applied,DamageModel.blockedFixedIncrement(applied,intent.material(),settings));
        }catch(RuntimeException|LinkageError invalid){return null;}
    }
    private static PlotOwnerFence.Owner owner(MoveTransactionJournal.BeforeImage b){return PlotOwnerFence.Owner.of(PlotKeyCodec.real(b.source()));}
    @Override public boolean acquire(MoveTransactionJournal.BeforeImage b){
        if(!isServerThread()||ownerMoves.containsKey(owner(b))||!service.acquireMove(b))return false;
        ownerMoves.put(owner(b),b.id());return true;
    }
    private MoveTransactionExecutor.Gate gate(MoveTransactionJournal.BeforeImage b,MoveTransactionExecutor.Gate result,String reason){
        if(TRACE_MOVES&&(result==MoveTransactionExecutor.Gate.BLOCKED||result==MoveTransactionExecutor.Gate.UNKNOWN))
            LOG.info("[PDD-DEFORM] phase=PLOT_GATE moveId={} owner={} source={} target={} result={} reason={} reservedQ={} blockedCandidateQ={} blockedAppliedQ={}",
                    b.id(),owner(b).uuid(),b.source(),b.destinationBefore(),result,reason,b.reservedQ(),b.blockedCandidateQ(),b.blockedAppliedQ());
        return result;
    }
    private Cell local(Cell c,BlockPos p){return locate(PlotKeyCodec.storage(at(c.real(),p,"PDD:MOVE_PROBE")),false);}
    private boolean authorized(Cell c){var actor=FakePlayerFactory.get(c.level(),ACTOR);return actor.mayBuild()&&c.level().mayInteract(actor,c.allocated());}
    private MoveTransactionExecutor.Gate dependencies(Cell c,Cell other){
        for(Direction face:Direction.values()){
            Cell adjacent=local(c,c.local().relative(face));
            if(!sameOwner(c,adjacent))return MoveTransactionExecutor.Gate.DEFERRED;
            if(adjacent.local().equals(other.local())||empty(adjacent))continue;
            var s=state(adjacent);
            // Unproved attached geometry or block-entity side effects are vetoed.
            if(hasBE(adjacent)||s.getBlock() instanceof FallingBlock||!s.getFluidState().isEmpty()
                    ||s.isSignalSource()||!s.isCollisionShapeFullBlock(adjacent.accessor(),adjacent.local()))return MoveTransactionExecutor.Gate.BLOCKED;
        }
        return MoveTransactionExecutor.Gate.APPROVED;
    }
    private static boolean assemblyGlue(Entity entity){
        return MoveSafety.isAssemblyGlue(entity.getClass().getName(),
                String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())));
    }
    private static MoveTransactionExecutor.Gate plotEntityGate(Cell c,Entity entity){
        boolean glue=assemblyGlue(entity);
        return MoveSafety.plotEntityGate(glue,glue&&ownGlueRegion(c,entity));
    }
    private static boolean ownGlueRegion(Cell c,Entity entity){
        if(entity.level()!=c.level())return false;
        var container=SubLevelContainer.getContainer(c.level());
        return container!=null&&MoveSafety.glueWithinPlot(entity.getBoundingBox(),entity.getX(),entity.getZ(),
                chunk->container.getPlot(chunk)==c.sub().getPlot());
    }
    private static MoveTransactionExecutor.Gate entityGate(Cell c){
        // The EmbeddedPlotLevelAccessor translates this LOCAL AABB by its center.
        // Retain verified assembly regions in place. A moved block may leave
        // that region; never move, clone, trim or remove its glue entity.
        BlockPos p=c.local();var area=new AABB(p.getX(),p.getY(),p.getZ(),p.getX()+1d,p.getY()+1d,p.getZ()+1d).inflate(0.001);
        var result=MoveTransactionExecutor.Gate.APPROVED;
        for(Entity entity:c.accessor().getEntities((Entity)null,area,e->!e.isSpectator())){
            var gate=plotEntityGate(c,entity);
            if(gate==MoveTransactionExecutor.Gate.BLOCKED)return gate;
            if(gate==MoveTransactionExecutor.Gate.UNKNOWN)result=gate;
        }
        return result;
    }
    private static double selfMass(Cell c){
        var tracker=c.sub().getSelfMassTracker();
        if(tracker==null)return Double.NaN;
        var center=tracker.getCenterOfMass();double mass=tracker.getMass();
        return center!=null&&Double.isFinite(center.x())&&Double.isFinite(center.y())&&Double.isFinite(center.z())
                &&Double.isFinite(mass)&&mass>0?mass:Double.NaN;
    }
    private static double blockMass(Cell c,BlockState s){return PhysicsBlockPropertyHelper.getMass(c.level(),c.allocated(),s);}
    /** The accessor handles allocation-space entities; additionally prove the
     * swept voxel's actual physical World AABB free of static collision and
     * other sublevel bounds. A broad-phase overlap is uncertainty, not proof
     * of blockage, hence never consumes the reserve. No chunks are requested. */
    private static MoveTransactionExecutor.Gate physicalClear(Cell c){
        var live=c.sub().logicalPose();var t=live.position();var r=live.rotationPoint();var s=live.scale();var q=live.orientation();
        var pose=new FrameMath.Pose(new FrameMath.Vec(t.x(),t.y(),t.z()),new FrameMath.Vec(r.x(),r.y(),r.z()),
                new FrameMath.Vec(s.x(),s.y(),s.z()),q.x(),q.y(),q.z(),q.w());
        if(!pose.valid()||Math.abs(s.x()-1)>1e-5||Math.abs(s.y()-1)>1e-5||Math.abs(s.z()-1)>1e-5)
            return MoveTransactionExecutor.Gate.UNKNOWN;
        double minX=Double.POSITIVE_INFINITY,minY=minX,minZ=minX,maxX=Double.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
        for(int dx=0;dx<=1;dx++)for(int dy=0;dy<=1;dy++)for(int dz=0;dz<=1;dz++){
            var p=pose.toWorldPoint(new FrameMath.Vec(c.allocated().getX()+dx,c.allocated().getY()+dy,c.allocated().getZ()+dz));
            if(!p.finite()||Math.abs(p.x())>=Integer.MAX_VALUE-2d||Math.abs(p.y())>=Integer.MAX_VALUE-2d||Math.abs(p.z())>=Integer.MAX_VALUE-2d)
                return MoveTransactionExecutor.Gate.UNKNOWN;
            minX=Math.min(minX,p.x());minY=Math.min(minY,p.y());minZ=Math.min(minZ,p.z());
            maxX=Math.max(maxX,p.x());maxY=Math.max(maxY,p.y());maxZ=Math.max(maxZ,p.z());
        }
        var area=new AABB(minX,minY,minZ,maxX,maxY,maxZ).deflate(1e-7);
        var container=SubLevelContainer.getContainer(c.level());
        if(container==null)return MoveTransactionExecutor.Gate.UNKNOWN;
        for(var other:container.queryIntersecting(new BoundingBox3d(area)))if(other!=c.sub())return MoveTransactionExecutor.Gate.UNKNOWN;
        for(int x=(int)Math.floor(area.minX);x<Math.ceil(area.maxX);x++)
            for(int y=(int)Math.floor(area.minY);y<Math.ceil(area.maxY);y++)
                for(int z=(int)Math.floor(area.minZ);z<Math.ceil(area.maxZ);z++){
                    var pos=new BlockPos(x,y,z);var chunk=new ChunkPos(pos);
                    if(!c.level().isInWorldBounds(pos)||container.inBounds(chunk)||container.getPlot(chunk)!=null)
                        return MoveTransactionExecutor.Gate.UNKNOWN;
                    if(c.level().getChunkSource().getChunkNow(chunk.x,chunk.z)==null)return MoveTransactionExecutor.Gate.DEFERRED;
                    var state=c.level().getBlockState(pos);
                    if(!state.isAir()||!state.getFluidState().isEmpty())return MoveTransactionExecutor.Gate.UNKNOWN;
                }
        return c.level().getEntitiesOfClass(Entity.class,area,e->!e.isSpectator()
                &&plotEntityGate(c,e)!=MoveTransactionExecutor.Gate.APPROVED).isEmpty()
                ?MoveTransactionExecutor.Gate.APPROVED:MoveTransactionExecutor.Gate.UNKNOWN;
    }
    private boolean registeredMaterialMatches(MoveTransactionJournal.BeforeImage b){
        var snapshot=MaterialRegistry.snapshot();
        var resolved=TargetMaterials.read(server,b.source(),snapshot);
        return b.sourceState()!=null&&snapshot.generation()==b.sourceState().materialGeneration()
                &&b.material()!=null&&resolved!=null&&b.material().equals(resolved.material())
                &&b.materialRevision().equals(resolved.revision());
    }
    @Override public MoveTransactionExecutor.Gate validate(MoveTransactionJournal.BeforeImage b){
        return validate(b,false);
    }
    private MoveTransactionExecutor.Gate validate(MoveTransactionJournal.BeforeImage b,boolean forceLive){
        final var unknown=MoveTransactionExecutor.Gate.UNKNOWN;final var blocked=MoveTransactionExecutor.Gate.BLOCKED;
        final var deferred=MoveTransactionExecutor.Gate.DEFERRED;
        try{
            if(!isServerThread()||service.moveInvalidated(b)||!b.id().equals(ownerMoves.get(owner(b))))return unknown;
            Cell a=locate(b.source(),false),d=locate(b.destinationBefore(),false);
            if(!sameOwner(a,d))return deferred;
            // The pending coordinator rebuilds this envelope before its next
            // attempt; an unobserved destination can never reach PREPARED.
            if(UNOBSERVED_DEST.equals(b.destinationBefore().fingerprint()))return deferred;
            if(!fp(state(a)).equals(b.source().fingerprint())||!fp(state(d)).equals(b.destinationBefore().fingerprint())
                    ||service.moveRevision(b.source())!=b.sourceRevision()||service.moveRevision(b.destinationBefore())!=b.destinationRevision()
                    ||!service.incarnation(PlotKeyCodec.real(b.source()),a.sub().getPlot().getCenterBlock()).equals(b.sourceState().incarnation())
                    ||!registeredMaterialMatches(b))return gate(b,unknown,"STALE_BEFORE_STATE_OR_MATERIAL");
            if(deferBreakReason(a,forceLive)!=null)return deferred;
            if(!Double.isFinite(selfMass(a)))return gate(b,unknown,"SABLE_MASS_UNAVAILABLE");
            if(!authorized(a)||!authorized(d))return gate(b,blocked,"MOVE_CLAIM_DENIED");
            if(!clearPath(d))return gate(b,blocked,"DESTINATION_OCCUPIED");
            BlockState s=state(a);
            if(s.isAir()||s.getDestroySpeed(a.accessor(),a.local())<0)return gate(b,unknown,"SOURCE_INELIGIBLE");
            if(!s.getFluidState().isEmpty()||s.getBlock() instanceof FallingBlock||s.isSignalSource()
                    ||s.getBlock() instanceof DoorBlock||s.getBlock() instanceof BedBlock||s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                    ||hasBE(a)&&payload(b)==null||s.getCollisionShape(a.accessor(),a.local()).isEmpty()
                    ||!MoveSafety.unitShape(a.level(),a.allocated(),s))return gate(b,blocked,"VETO_SOURCE_DEPENDENCY_OR_BE");
            if(!payloadMatches(a,b))return gate(b,unknown,"SOURCE_BE_CHANGED");
            // Both axis corridor cells, not just the selected ordering, must be clear.
            for(BlockPos p:MoveSafety.path(a.local(),d.local())){
                Cell step=local(a,p);
                if(!sameOwner(a,step))return deferred;
                if(!clearPath(step))return gate(b,blocked,"PLOT_CORRIDOR_OCCUPIED");
                if(!authorized(step))return gate(b,blocked,"PLOT_CORRIDOR_CLAIM_DENIED");
                var occupancy=entityGate(step);
                if(occupancy!=MoveTransactionExecutor.Gate.APPROVED)
                    return gate(b,occupancy,occupancy==blocked?"PLOT_CORRIDOR_ENTITY":"PLOT_CORRIDOR_GLUE_OWNER_UNVERIFIED");
                var physical=physicalClear(step);
                if(physical!=MoveTransactionExecutor.Gate.APPROVED)return gate(b,physical,"PHYSICAL_ROUTE_UNVERIFIED");
            }
            var occupancy=entityGate(a);
            if(occupancy!=MoveTransactionExecutor.Gate.APPROVED)
                return gate(b,occupancy,occupancy==blocked?"PLOT_SOURCE_ENTITY":"PLOT_SOURCE_GLUE_OWNER_UNVERIFIED");
            if(!s.canSurvive(d.accessor(),d.local()))return gate(b,blocked,"VETO_NEIGHBOR_SUPPORT_OR_KINETIC");
            var dependency=dependencies(a,d);
            if(dependency!=MoveTransactionExecutor.Gate.APPROVED)return gate(b,dependency,"SOURCE_NEIGHBOR_UNSAFE");
            dependency=dependencies(d,a);
            if(dependency!=MoveTransactionExecutor.Gate.APPROVED)return gate(b,dependency,"DEST_NEIGHBOR_UNSAFE");
            dependency=MoveSafety.checkConnections(a.level(),a.allocated(),s,e->plotEntityGate(a,e));
            if(dependency!=MoveTransactionExecutor.Gate.APPROVED)return gate(b,dependency,"VETO_SOURCE_GLUE_OR_KINETIC");
            dependency=MoveSafety.checkConnections(d.level(),d.allocated(),Blocks.AIR.defaultBlockState(),e->plotEntityGate(d,e));
            if(dependency!=MoveTransactionExecutor.Gate.APPROVED)return gate(b,dependency,"VETO_DEST_GLUE_OR_KINETIC");
            // SableCommonEvents.handleBlockChange calls HeatMap onSolidAdded/
            // onSolidRemoved on the actual air<->nonair transition (2.0.6).
            var topology=PlotMoveTopology.check(a.local(),d.local(),pos->{Cell c=local(a,pos);return sameOwner(a,c)?!state(c).isAir():null;});
            if(topology==PlotMoveTopology.Result.DISCONNECTED)return gate(b,blocked,"PLOT_CONNECTIVITY_ISOLATION");
            if(topology==PlotMoveTopology.Result.UNKNOWN)return deferred;
            return MoveTransactionExecutor.Gate.APPROVED;
        }catch(RuntimeException|LinkageError unsafe){return gate(b,unknown,"MOVE_PREFLIGHT_EXCEPTION_"+unsafe.getClass().getSimpleName());}
    }
    private boolean write(Cell c,BlockState value){
        ownMoveWrite=PlotKeyCodec.storage(c.real());ownMoveBefore=state(c);ownMoveAfter=value;ownMoveWitnesses=0;
        try{boolean result=c.accessor().setBlock(c.local(),value,MOVE_FLAGS,512);return result&&ownMoveWitnesses==1;}
        finally{ownMoveWrite=null;ownMoveBefore=null;ownMoveAfter=null;}
    }
    private boolean bothBefore(MoveTransactionJournal.BeforeImage b){
        Cell a=locate(b.source(),false),d=locate(b.destinationBefore(),false);
        return sameOwner(a,d)&&!service.moveInvalidated(b)&&fp(state(a)).equals(b.source().fingerprint())
                &&fp(state(d)).equals(b.destinationBefore().fingerprint())&&clearPath(d)&&payloadMatches(a,b);
    }
    private boolean bothAfter(MoveTransactionJournal.BeforeImage b){
        Cell a=locate(b.source(),false),d=locate(b.destinationAfter().key(),false);
        return sameOwner(a,d)&&!service.moveInvalidated(b)&&empty(a)&&fp(state(d)).equals(b.destinationAfter().key().fingerprint())&&payloadMatches(d,b);
    }
    private MoveTransactionExecutor.Readback waitForHeat(MoveTransactionJournal.BeforeImage b,MoveTransactionExecutor.Readback expected,double expectedMass){
        if(!Double.isFinite(expectedMass)||expectedMass<=0)return MoveTransactionExecutor.Readback.UNCERTAIN;
        moveWaits.put(b.id(),new MoveWait(server.getTickCount(),expected,expectedMass));
        if(TRACE_MOVES)LOG.info("[PDD-DEFORM] phase=PLOT_HEATMAP_WAIT moveId={} owner={} source={} dest={} mutationServerTick={} expected={} expectedSelfMass={}",
                b.id(),owner(b).uuid(),b.source(),b.destinationAfter().key(),server.getTickCount(),expected,expectedMass);
        return MoveTransactionExecutor.Readback.WAITING;
    }
    private MoveTransactionExecutor.Readback rollbackDestination(MoveTransactionJournal.BeforeImage b,BlockState oldDestination,double originalMass){
        Cell a=locate(b.source(),false),d=locate(b.destinationAfter().key(),false);
        if(!sameOwner(a,d)||service.moveInvalidated(b)||!fp(state(a)).equals(b.source().fingerprint())
                ||!payloadMatches(a,b)||!fp(state(d)).equals(b.destinationAfter().key().fingerprint()))return MoveTransactionExecutor.Readback.UNCERTAIN;
        // Move flag suppresses decorative Copycats material drops. Do not clear
        // a destination changed by a claim handler or unrelated callback.
        if(!payloadMatches(d,b)||!write(d,oldDestination)||!bothBefore(b))return MoveTransactionExecutor.Readback.UNCERTAIN;
        return waitForHeat(b,MoveTransactionExecutor.Readback.RESTORED_BEFORE_IMAGE,originalMass);
    }
    @Override public MoveTransactionExecutor.Readback mutateOnce(MoveTransactionJournal.BeforeImage b){
        if(validate(b,true)!=MoveTransactionExecutor.Gate.APPROVED)return MoveTransactionExecutor.Readback.UNCERTAIN;
        Cell a=locate(b.source(),false),d=locate(b.destinationBefore(),false);
        if(!sameOwner(a,d))return MoveTransactionExecutor.Readback.UNCERTAIN;
        try{
            var actor=FakePlayerFactory.get(a.level(),ACTOR);var original=state(a);var oldDestination=state(d);
            var sourceVeto=new BlockEvent.BreakEvent(a.level(),a.allocated(),original,actor);
            NeoForge.EVENT_BUS.post(sourceVeto);
            if(sourceVeto.isCanceled())return bothBefore(b)?MoveTransactionExecutor.Readback.RESTORED_BEFORE_IMAGE:MoveTransactionExecutor.Readback.UNCERTAIN;
            if(!bothBefore(b)||validate(b,true)!=MoveTransactionExecutor.Gate.APPROVED)return MoveTransactionExecutor.Readback.UNCERTAIN;
            double originalMass=selfMass(a),fromMass=blockMass(a,original),oldDestinationMass=blockMass(d,oldDestination);
            if(!Double.isFinite(originalMass)||!Double.isFinite(fromMass)||fromMass<0
                    ||!Double.isFinite(oldDestinationMass)||oldDestinationMass<0)return MoveTransactionExecutor.Readback.UNCERTAIN;
            var snapshot=BlockSnapshot.create(d.level().dimension(),d.level(),d.allocated(),MOVE_FLAGS);
            lastBreakAttempt.put(owner(b),new PlotBreakPacing.Attempt(server.getTickCount(),false));
            boolean placed=write(d,original);
            if(!placed)return bothBefore(b)?waitForHeat(b,MoveTransactionExecutor.Readback.RESTORED_BEFORE_IMAGE,originalMass):MoveTransactionExecutor.Readback.UNCERTAIN;
            d=locate(b.destinationAfter().key(),false);a=locate(b.source(),false);
            if(!sameOwner(a,d)||service.moveInvalidated(b)||!fp(state(d)).equals(b.source().fingerprint())
                    ||!fp(state(a)).equals(b.source().fingerprint())||!payloadMatches(a,b))return MoveTransactionExecutor.Readback.UNCERTAIN;
            String be=payload(b);
            if(be!=null&&!CopycatMovePayload.restore(d.accessor(),d.local(),state(d),d.level().registryAccess(),be))
                // Roll back only if the normal setter and the whole installed
                // payload can both be proven. Otherwise quarantine, never drop.
                return rollbackDestination(b,oldDestination,originalMass);
            if(!payloadMatches(d,b))return MoveTransactionExecutor.Readback.UNCERTAIN;
            var placeVeto=new BlockEvent.EntityPlaceEvent(snapshot,original,actor);
            NeoForge.EVENT_BUS.post(placeVeto);
            if(placeVeto.isCanceled())return rollbackDestination(b,oldDestination,originalMass);
            if(service.moveInvalidated(b)||!authorized(d)||!authorized(a)||!fp(state(a)).equals(b.source().fingerprint())
                    ||!payloadMatches(a,b)||!fp(state(d)).equals(b.destinationAfter().key().fingerprint())||!payloadMatches(d,b))return MoveTransactionExecutor.Readback.UNCERTAIN;
            double installedMass=blockMass(d,state(d));
            if(!Double.isFinite(installedMass)||installedMass<0)return rollbackDestination(b,oldDestination,originalMass);
            if(!write(a,Blocks.AIR.defaultBlockState()))return rollbackDestination(b,oldDestination,originalMass);
            if(!bothAfter(b))return MoveTransactionExecutor.Readback.UNCERTAIN;
            return waitForHeat(b,MoveTransactionExecutor.Readback.CONFIRMED,originalMass+installedMass-oldDestinationMass-fromMass);
        }catch(RuntimeException|LinkageError unsafe){
            LOG.warn("[PDD-DEFORM] phase=PLOT_MOVE_EXCEPTION moveId={} result=UNCERTAIN",b.id(),unsafe);
            return MoveTransactionExecutor.Readback.UNCERTAIN;
        }
    }
    @Override public MoveTransactionExecutor.Readback readback(MoveTransactionJournal.BeforeImage b){
        try{
            var wait=moveWaits.get(b.id());
            if(wait==null||service.moveInvalidated(b))return MoveTransactionExecutor.Readback.UNCERTAIN;
            if(fence.orphaned(owner(b))||service.isSplitParent(owner(b).dimension(),owner(b).uuid()))return MoveTransactionExecutor.Readback.UNCERTAIN;
            Cell a=locate(b.source(),false),d=locate(b.destinationAfter().key(),false);
            if(!sameOwner(a,d))return MoveTransactionExecutor.Readback.WAITING;
            if(wait.expected()==MoveTransactionExecutor.Readback.CONFIRMED?!bothAfter(b):!bothBefore(b))return MoveTransactionExecutor.Readback.UNCERTAIN;
            long complete=(Object)a.sub() instanceof PlotHeatMapTickWitness witness?witness.pdd$completedHeatMapServerTick():-1;
            if(complete<=wait.tick()||deferBreakReason(a,true)!=null)return MoveTransactionExecutor.Readback.WAITING;
            double actualMass=selfMass(a),tolerance=Math.max(1e-6,Math.abs(wait.expectedMass())*1e-9);
            if(!Double.isFinite(actualMass)||Math.abs(actualMass-wait.expectedMass())>tolerance){
                LOG.warn("[PDD-DEFORM] phase=PLOT_MASS_MISMATCH moveId={} expectedMass={} actualMass={} tolerance={} action=QUARANTINE",b.id(),wait.expectedMass(),actualMass,tolerance);
                return MoveTransactionExecutor.Readback.UNCERTAIN;
            }
            if(TRACE_MOVES)LOG.info("[PDD-DEFORM] phase=PLOT_POSTSTATE moveId={} owner={} source={} dest={} mutationTick={} completedHeatMapTick={} result={} reservedQ={} expectedSelfMass={} actualSelfMass={} nativeUpdate=NORMAL_SETTER_COLLIDER_NOT_INDEPENDENTLY_MEASURED",
                    b.id(),owner(b).uuid(),b.source(),b.destinationAfter().key(),wait.tick(),complete,wait.expected(),b.reservedQ(),wait.expectedMass(),actualMass);
            lastBreakAttempt.put(owner(b),new PlotBreakPacing.Attempt(server.getTickCount(),true));
            return wait.expected();
        }catch(RuntimeException|LinkageError unsafe){return MoveTransactionExecutor.Readback.UNCERTAIN;}
    }
    @Override public void release(MoveTransactionJournal.BeforeImage b){
        moveWaits.remove(b.id());ownerMoves.remove(owner(b),b.id());service.releaseMove(b);
    }
    @Override public void quarantine(MoveTransactionJournal.BeforeImage b,String reason){
        moveWaits.remove(b.id());ownerMoves.remove(owner(b),b.id());service.quarantineMove(b,reason);
        LOG.warn("[PDD-DEFORM] phase=PLOT_QUARANTINE moveId={} owner={} source={} dest={} reason={} action=NO_REPLAY",
                b.id(),owner(b).uuid(),b.source(),b.destinationAfter().key(),reason);
    }
    @Override public MoveTransactionExecutor.Settlement settleBlocked(MoveTransactionJournal.BeforeImage b){return service.settleBlockedMove(b);}
    @Override public boolean breakVanillaOnce(SparseStateKey virtual){
        lastBreakKey=null;
        lastBreakWitness=false;
        if(!isServerThread())return false;
        Cell cell=locate(virtual,false);
        if(cell==null||inMutation||ownerMoves.containsKey(PlotOwnerFence.Owner.of(cell.real())))return false;
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
        // Last defensive check at the irreversible call boundary. The core has
        // already checked before PREPARED; a newly uncertain state is not retried.
        if(deferBreakReason(cell,true)!=null)return false;
        PlotBreakMutationWitness.begin(cell.level(),cell.allocated(),expected);
        // Reserve the owner slot BEFORE the irreversible Sable API call.
        // A false return/exception is still one attempted mutation.
        lastBreakAttempt.put(PlotOwnerFence.Owner.of(cell.real()),
                new PlotBreakPacing.Attempt(server.getTickCount(),false));
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
        if(apiResult && virtual.equals(lastBreakKey) &&
                (status==WorldCommitCore.BreakReadback.CONFIRMED_AIR
                    || status==WorldCommitCore.BreakReadback.CONFIRMED_AIR_MUTATION_WITNESS)){
            var owner=PlotOwnerFence.Owner.of(PlotKeyCodec.real(virtual));
            var attempt=lastBreakAttempt.get(owner);
            if(attempt!=null && attempt.tick()==server.getTickCount())
                lastBreakAttempt.put(owner,new PlotBreakPacing.Attempt(attempt.tick(),true));
        }
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
