package com.physicaldestroydeform.plot;

import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.deform.*;
import com.physicaldestroydeform.stress.*;
import com.physicaldestroydeform.collision.ContactCreditLedger;
import com.physicaldestroydeform.material.MaterialRegistry;
import com.physicaldestroydeform.state.SparseStateKey;
import com.physicaldestroydeform.world.WorldCommitCore;
import com.physicaldestroydeform.world.WorldPersistentJournal;
import com.physicaldestroydeform.world.WorldReceiptJournal;
import com.physicaldestroydeform.world.LineageJournal;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

/** 0.5.1: real Plot commit with independent durable WAL and stale-owner quarantine.
 * Shared Q is sealed upstream; no Sable callback, physics solver or source ledger is modified.
 * All mutations happen in ServerTickEvent.Post, not inside a Rapier step.
 */
public final class PlotCommitService {
    private static final int MAX_RECOVERY_PER_TICK=8, MAX_LOGS_PER_TICK=
            com.physicaldestroydeform.diagnostics.TraceBudget.cap(4,16);
    private record Coordinate(String dimension,int x,int y,int z) {
        Coordinate(SparseStateKey k){this(k.dimension(),k.x(),k.y(),k.z());}
    }
    private final MinecraftServer server;
    private final Logger log;
    private final WorldPersistentJournal journal;
    private final WorldCommitCore core;
    private StressRuntime stress;
    private final Set<Coordinate> stressWatched=new HashSet<>();
    private final NeoForgePlotGateway gateway;
    private final MoveRuntime movement;
    private final int mutationCap;
    private final Deque<Coordinate> orphanCleanup=new ArrayDeque<>();
    private final Set<Coordinate> orphanCleanupSet=new HashSet<>();
    private final DamageModel.Settings damageSettings;
    private final Deque<WorldReceiptJournal.Receipt> unresolved=new ArrayDeque<>();
    private final Set<PlotOwnerFence.Owner> quarantined=new HashSet<>();
    private final Set<String> knownSplitParents=new HashSet<>();
    private final Map<String,UUID> verifiedLineage=new HashMap<>();
    private final Set<Coordinate> lineageActive=new HashSet<>();
    private final Map<Coordinate,Long> lineageQuarantine=new HashMap<>();
    private final Map<PlotOwnerFence.Owner,Set<Coordinate>> active=new HashMap<>();
    private final Map<UUID,Set<PlotOwnerFence.Owner>> activeOwnersByUuid=new HashMap<>();
    // Startup-only owners remain watched before Sable has loaded/bound their Plot.
    private final Map<UUID,Set<PlotOwnerFence.Owner>> restoredOwners=new HashMap<>();
    private SparseStateKey ownBreak;
    private boolean fault;
    private long lastLifecycleTick=-1;
    private long accepted,rejected,damaged,broken,blocked,splitOrphans,lastSummary,suppressedCommitLogs;
    private long lastSourceLogTick=Long.MIN_VALUE;
    private int sourceLogs;
    public PlotCommitService(MinecraftServer server,DamageModel.Settings settings,Logger log) throws IOException {
        this.server=Objects.requireNonNull(server);this.log=Objects.requireNonNull(log);
        this.damageSettings=Objects.requireNonNull(settings);
        Path parent=server.getWorldPath(LevelResource.ROOT).resolve("data");
        journal=new WorldPersistentJournal(parent.resolve("pdd-plot-0.5.1"),
                parent.resolve("pdd-plot-0.5.1-legacy-absent.wal"));
        int configured=Integer.getInteger("pdd.plotMutationCapPerTick",32);
        int cap=Math.max(1,Math.min(4096,configured));
        mutationCap=cap;
        core=new WorldCommitCore(journal,settings,cap);
        gateway=new NeoForgePlotGateway(server,this);
        movement=new MoveRuntime(parent.resolve("pdd-plot-moves-0.6.0"),journal,gateway.movementGateway(),gateway::planMove,log);
        core.setDeformationHandler(movement::afterDamage);
        core.setExecutionHold(i->movement.busy()||(stress!=null&&stress.holds(i)));
        core.setRollbackHold(i->stress!=null&&stress.holdsForClockRollback(i));
        for(var state:journal.stateSnapshot())registerState(state.key());
        for(var receipt:journal.quarantinedSnapshot()){
            if(LineageJournal.isMarker(receipt)){restoreLineageQuarantine(receipt);continue;}
            if(receipt.id().startsWith("ORPHAN:1:")) {
                try {
                    var owner=PlotOwnerFence.Owner.of(PlotKeyCodec.real(receipt.key()));
                    quarantined.add(owner);gateway.fence().orphan(owner);
                }catch(RuntimeException malformed){throw new IOException("malformed persistent Plot orphan marker",malformed);}
            }
        }
        int restored=0;
        for(var receipt:journal.uncertain()){
            if(LineageJournal.isMarker(receipt)){
                if(LineageJournal.isProofMarker(receipt)){watchRecoveredLineage(receipt.key());continue;}
                LineageJournal.quarantine(journal,receipt.id(),server.getTickCount());
                restoreLineageQuarantine(receipt);continue;
            }
            if(movement.recorded(receipt.id()))continue;
            if(receipt.pending()!=null){
                var owner=PlotOwnerFence.Owner.of(PlotKeyCodec.real(receipt.key()));
                restoredOwners.computeIfAbsent(owner.uuid(),ignored->new HashSet<>()).add(owner);
                restored++;
            }else{
                unresolved.add(receipt);
                // Prevent any sibling pending intent on a PREPARED owner before
                // the bounded recovery pass writes its orphan marker.
                if(receipt.status()==WorldReceiptJournal.Status.PREPARED)
                    gateway.fence().orphan(PlotOwnerFence.Owner.of(PlotKeyCodec.real(receipt.key())));
            }
        }
        movement.recover();
        log.info("[PDD-PLOT-RECOVERY] phase=START restoredPending={} legacyOrPrepared={} intentPayloadPersisted=true",
                restored,unresolved.size());
        // Power loss after the durable orphan marker but before state tombstones:
        // re-queue cleanup without allowing stale data to become live.
        for(var owner:quarantined){
            for(Coordinate cell:active.getOrDefault(owner,Set.of()))queueOrphanCleanup(cell);
            removeActiveOwner(owner);
            log.info("[PDD-PLOT-ORPHAN] phase=RECOVERY owner={} generation={} " +
                    "cleanupQueued={} marker=RESTORED transfer=FORBIDDEN",
                    owner.uuid(),owner.generation(),orphanCleanup.size());
        }
        // No old sparse damage may become live merely because a new ship uses the same coordinates.
        log.info("[PDD-PLOT] phase=START revision=0.5.1 cap={} BE=VETO_UNSAFE " +
                        "loaded=REQUIRED ownerEpoch=REQUIRED split=ORPHAN_QUARANTINE " +
                        "storedDamage={} receipt={} unresolved={} orphaned={} schema={} " +
                        "massCollider=SABLE_NORMAL_BLOCK_CHANGE_HOOK_NOT_INDEPENDENTLY_VERIFIED",
                cap,journal.size(),journal.receiptCount(),unresolved.size(),quarantined.size(),
                WorldPersistentJournal.SCHEMA);
        log.info("[PDD-PLOT-SAFE] phase=POLICY source=SABLE_2_0_6_HEATMAP_READ_ONLY tickWitness=REQUIRED_CURRENT_SERVER_TICK " +
                "breakSpacingTicks={} uncertainSpacingMin=3 pendingAction=DEFER_BEFORE_PREPARED " +
                "unknownState=FAIL_CLOSED splitAndNativeSave=NOT_INDEPENDENTLY_VERIFIED",
                gateway.breakSpacingTicks());
    }
    private void unregisterState(SparseStateKey virtual){
        if(!PlotKeyCodec.isPlotStorage(virtual))return;
        var owner=PlotOwnerFence.Owner.of(PlotKeyCodec.real(virtual));
        var cells=active.get(owner);
        if(cells!=null){
            cells.remove(new Coordinate(virtual));
            if(cells.isEmpty())removeActiveOwner(owner);
        }
    }
    private void removeActiveOwner(PlotOwnerFence.Owner owner){
        active.remove(owner);
        var owners=activeOwnersByUuid.get(owner.uuid());
        if(owners!=null){owners.remove(owner);if(owners.isEmpty())activeOwnersByUuid.remove(owner.uuid());}
    }
    private void registerState(SparseStateKey virtual){
        if(!PlotKeyCodec.isPlotStorage(virtual))return;
        var owner=PlotOwnerFence.Owner.of(PlotKeyCodec.real(virtual));
        active.computeIfAbsent(owner,k->{
            activeOwnersByUuid.computeIfAbsent(owner.uuid(),ignored->new HashSet<>()).add(owner);
            return new HashSet<>();
        }).add(new Coordinate(virtual));
    }
    private void queueOrphanCleanup(Coordinate position){
        if(orphanCleanupSet.add(position))orphanCleanup.addLast(position);
    }
    public String incarnation(SparseStateKey real,BlockPos center){
        var virtual=PlotKeyCodec.storage(real);
        long revision=journal.revision(virtual.dimension(),real.x(),real.y(),real.z());
        return PlotIncarnation.token(real,center.getX(),center.getY(),center.getZ(),revision);
    }
    /** Called from the same immutable granted Q as WorldCommitService, once per accepted source. */
    public void offer(ContactCreditLedger.Grant grant,MaterialRegistry.Snapshot materials){
        offer(grant,materials,new com.physicaldestroydeform.material.TargetMaterials(server,grant.id().pair().dimension(),materials));
    }
    public void offer(ContactCreditLedger.Grant grant,MaterialRegistry.Snapshot materials,com.physicaldestroydeform.material.TargetMaterials targetMaterials){
        if(!server.isSameThread()||fault||core.ioFault()){
            rejected++;log.error("[PDD-PLOT] phase=OFFER reason=SERVER_OR_SAVE_UNSAFE credit={}",grant.id());return;
        }
        try {
            var projection=PlotIntentMapper.projectTargets(grant,materials.generation(),
                    targetMaterials::resolve,original->{
                        var owner=PlotOwnerFence.Owner.of(original);
                        if(quarantined.contains(owner)||!gateway.bindOnOffer(original))return null;
                        var virtual=PlotKeyCodec.storage(original);
                        var observed=gateway.readIfLoaded(virtual);
                        return observed!=null && observed.loaded() && !observed.isAir()
                                && observed.fingerprint().equals(original.fingerprint())
                                ?observed.incarnation():null;
                    },targetMaterials::direction);
            try{
                if(stress!=null){
                    boolean reserved=stress.reserve(projection.plot(),materials,damageSettings);
                    if(!projection.plot().isEmpty()||projection.missingOwnerQ()>0)
                        log.info("[PDD-PLOT] phase=RESERVED credit={} plotTargets={} QPlot={} queued={} reserved={} QWorldUnchanged={} QMissingMaterial={} QUnavailableOwner={} QRejected={} QUnused={} handoff=BOUNDED_STRESS_SPOOL",
                                grant.id(),projection.plot().size(),projection.plot().stream().mapToDouble(WorldCommitCore.Intent::sealedQ).sum(),core.queued(),reserved,projection.worldQ(),projection.missingMaterialQ(),projection.missingOwnerQ(),projection.rejectedQ(),grant.unused());
                    return;
                }
            }catch(StressReservationJournal.CapacityException pressure){
                rejected++;log.warn("[PDD-STRESS-LEDGER] phase=ADMISSION_REJECT owner=PLOT reason={} credit={} roots={} QUnused={} QReissue=false",pressure.getMessage(),grant.id(),projection.plot().size(),projection.plot().stream().mapToDouble(WorldCommitCore.Intent::sealedQ).sum());return;
            }
            for(var intent:MoveRuntime.reverseOrder(projection.plot())){
                var decision=core.submit(intent,server.getTickCount(),server.overworld().getGameTime());
                if(decision.result()==WorldCommitCore.Outcome.DEFERRED){
                    accepted++;
                }else{
                    rejected++;
                    log.warn("[PDD-PLOT] phase=OFFER_REJECT id={} result={} reason={} target={}",
                            intent.applyId(),decision.result(),decision.reason(),PlotKeyCodec.real(intent.key()));
                }
            }
            if(!projection.plot().isEmpty() || projection.missingOwnerQ()>0){
                log.info("[PDD-PLOT] phase=OFFER credit={} plotTargets={} QPlot={} " +
                                "QWorldUnchanged={} QMissingMaterial={} QUnavailableOwner={} " +
                                "QRejected={} QUnused={} queued={}",
                        grant.id(),projection.plot().size(),
                        projection.plot().stream().mapToDouble(WorldCommitCore.Intent::sealedQ).sum(),
                        projection.worldQ(),projection.missingMaterialQ(),projection.missingOwnerQ(),
                        projection.rejectedQ(),grant.unused(),core.queued());
            }
        }catch(IOException|RuntimeException failed){
            rejected++;log.error("[PDD-PLOT] phase=OFFER reason=INVALID_SEALED_PLAN " +
                    "credit={} Q={} action=REJECT_NO_REISSUE",grant.id(),grant.issuedQ(),failed);
        }
    }
    public boolean faulted(){return fault||core.ioFault();}
    public WorldPersistentJournal lineageJournal(){return journal;}
    public void watchRecoveredLineage(SparseStateKey key){
        lineageActive.add(new Coordinate(key));registerState(key);
    }
    public boolean lineageRecoveryConflict(SparseStateKey key){
        return lineageQuarantine.containsKey(new Coordinate(key))
                ||movement.fences.quarantined(key)||movement.fences.held(key);
    }
    public PlotOwnerFence.Owner resolveLineageOwner(ServerSubLevel sub){
        if(faulted()||!server.isSameThread()||sub.isRemoved()||sub.getPlot()==null)return null;
        String dimension=sub.getLevel().dimension().location().toString();UUID uuid=sub.getUniqueId();
        var candidates=new HashSet<>(activeOwnersByUuid.getOrDefault(uuid,Set.of()));
        candidates.addAll(restoredOwners.getOrDefault(uuid,Set.of()));
        var bound=gateway.fence().owner(dimension,uuid);
        if(bound!=null)candidates.add(bound);
        candidates.removeIf(o->!o.dimension().equals(dimension));
        if(candidates.size()>1)return null;
        var owner=candidates.isEmpty()?new PlotOwnerFence.Owner(dimension,uuid,1):candidates.iterator().next();
        return !isSplitParent(dimension,uuid)&&gateway.bindLineage(owner,sub)?owner:null;
    }
    /** Saved epochs are ambiguous: retain exact coordinate before-images, but
     * never choose one by proximity or issue incoming state from this cell. */
    public int quarantineUnresolvedLineage(ServerLevel level,BlockPos allocated)throws IOException{
        var container=SubLevelContainer.getContainer(level);if(container==null)return 0;
        var nativePlot=container.getPlot(new ChunkPos(allocated));
        if(nativePlot==null||!(nativePlot.getSubLevel() instanceof ServerSubLevel sub))return 0;
        String dim=level.dimension().location().toString();
        BlockPos local=allocated.subtract(nativePlot.getCenterBlock());
        var candidates=new HashSet<>(activeOwnersByUuid.getOrDefault(sub.getUniqueId(),Set.of()));
        candidates.addAll(restoredOwners.getOrDefault(sub.getUniqueId(),Set.of()));
        int count=0;
        for(var owner:candidates){
            if(!owner.dimension().equals(dim))continue;
            var key=PlotKeyCodec.storage(new SparseStateKey(dim,"PLOT",owner.uuid().toString(),owner.generation(),
                    local.getX(),local.getY(),local.getZ(),"PDD:LINEAGE_UNRESOLVED"));
            for(var state:journal.statesAtPosition(key)){
                String marker=LineageJournal.quarantineSource(journal,state,server.getTickCount(),"OWNER_EPOCH_UNAVAILABLE");
                recordLineageQuarantine(marker);count++;
            }
        }
        return count;
    }
    private void restoreLineageQuarantine(WorldReceiptJournal.Receipt r){
        if(r.status()!=WorldReceiptJournal.Status.PREPARED&&r.status()!=WorldReceiptJournal.Status.PARTIAL_QUARANTINED)return;
        var cell=new Coordinate(r.key());long expiry=LineageJournal.quarantineThrough(r);
        if(LineageJournal.revision(journal,r.key())<=expiry){
            lineageQuarantine.merge(cell,expiry,Math::max);registerState(r.key());
        }
    }
    public boolean watchLineage(SparseStateKey key){
        if(faulted()||!server.isSameThread()||isMoveQuarantined(key)||movement.fences.held(key))return false;
        lineageActive.add(new Coordinate(key));registerState(key);return true;
    }
    public void recordLineageQuarantine(String marker){
        var r=journal.receipt(marker);if(LineageJournal.isMarker(r))restoreLineageQuarantine(r);
    }
    public void finishLineage(SparseStateKey key,String marker,boolean success){
        Coordinate cell=new Coordinate(key);lineageActive.remove(cell);
        if(!success){var r=journal.receipt(marker);if(LineageJournal.isMarker(r))restoreLineageQuarantine(r);}
        if(journal.get(key)!=null)registerState(key);
        else if(!lineageQuarantine.containsKey(cell))unregisterState(key);
    }
    public void lineageFault(Exception failure){fault=true;log.error("[PDD-TRANSFER] owner=PLOT status=IO_FAULT",failure);}
    /** Parent and child retain independent epochs. Only this native mapped move
     * exempts that exact parent/child split marker from legacy orphan cleanup. */
    public void confirmLineage(ServerSubLevel parent,ServerSubLevel child){
        if(parent==null||child==null||parent==child)return;
        String dimension=parent.getLevel().dimension().location().toString();
        if(child.getLevel()!=parent.getLevel())return;
        verifiedLineage.put(dimension+"|"+child.getUniqueId(),parent.getUniqueId());
        gateway.lineageChanged(dimension,parent.getUniqueId());
        gateway.lineageChanged(dimension,child.getUniqueId());
        log.info("[PDD-LINEAGE] parent={} child={} proof=NATIVE_MOVE_BLOCKS",parent.getUniqueId(),child.getUniqueId());
    }
    public boolean verifiedLineage(String dimension,UUID parent,UUID child){
        return parent.equals(verifiedLineage.get(dimension+"|"+child));
    }
    void clearLineage(String dimension,UUID child){verifiedLineage.remove(dimension+"|"+child);}
    public boolean isSplitParent(String dimension,UUID uuid){
        return knownSplitParents.contains(dimension+"|"+uuid);
    }
    /** Post-physics split detection; old-owner ghost state never migrates by matching XYZ. */
    public void quarantineParent(String dimension,UUID uuid){
        quarantineOwner(dimension,uuid,"SABLE_SPLIT_FROM_PARENT");
    }
    void quarantineRemoved(String dimension,UUID uuid){
        clearLineage(dimension,uuid);
        quarantineOwner(dimension,uuid,"SABLE_REMOVED");
    }
    private void quarantineOwner(String dimension,UUID uuid,String reason){
        if(!server.isSameThread()||fault)return;
        String parentKey=dimension+"|"+uuid;
        if(!knownSplitParents.add(parentKey))return;
        Set<PlotOwnerFence.Owner> known=new HashSet<>(active.keySet());
        known.addAll(gateway.fence().boundSnapshot());
        known.addAll(restoredOwners.getOrDefault(uuid,Set.of()));
        for(var receipt:unresolved){
            if(PlotKeyCodec.isPlotStorage(receipt.key()))
                known.add(PlotOwnerFence.Owner.of(PlotKeyCodec.real(receipt.key())));
        }
        for(var owner:known){
            if(owner.dimension().equals(dimension)&&owner.uuid().equals(uuid))
                orphan(owner,reason);
        }
    }
    /** Must precede collision credit mapping, including same-tick unload/reload. */
    public void refreshLifecycle(long tick){
        if(fault||core.ioFault())return;
        if(lastLifecycleTick!=tick||gateway.lifecycleDirty()){
            gateway.detectLifecycle();
            lastLifecycleTick=tick;
        }
        for(var owner:gateway.fence().drainNewOrphans())orphan(owner,"OWNER_ID_OR_EPOCH_CHANGED");
    }
    public NeoForgePlotGateway.LifecycleBinding lifecycleBinding(String dimension,UUID uuid){
        return gateway.lifecycleBinding(dimension,uuid);
    }
    private void orphan(PlotOwnerFence.Owner owner,String reason){
        if(quarantined.contains(owner))return;
        var virtual=PlotKeyCodec.storage(new SparseStateKey(owner.dimension(),"PLOT",
                owner.uuid().toString(),owner.generation(),0,0,0,"PDD:OWNER_ORPHAN"));
        String marker="ORPHAN:1:"+owner.dimension()+":"+owner.uuid()+":"+owner.generation();
        // WAL marker FIRST. A crash during cleanup still prevents state replay after restart.
        try {
            if(journal.receipt(marker)==null)journal.transition(
                    new WorldReceiptJournal.Receipt(marker,virtual,
                            WorldReceiptJournal.Status.PARTIAL_QUARANTINED,
                            "ORPHAN_QUARANTINE_"+reason,server.getTickCount()),null);
            quarantined.add(owner);gateway.fence().orphan(owner);
            int aborted=core.abortMatching(i->PlotOwnerFence.Owner.of(
                    PlotKeyCodec.real(i.key())).equals(owner),
                    "ORPHAN_QUARANTINE_"+reason,server.getTickCount());
            if(core.ioFault())throw new IOException("failed to terminalize orphaned queue");
            splitOrphans++;
            if(aborted>0)log.warn("[PDD-PLOT] phase=ABORT_SPLIT owner={} closedQueued={}",owner,aborted);
            for(Coordinate position:active.getOrDefault(owner,Set.of()))queueOrphanCleanup(position);
            removeActiveOwner(owner);
            log.warn("[PDD-PLOT] phase=OWNER_ORPHAN owner={} dimension={} generation={} " +
                            "reason={} stateTransfer=FORBIDDEN oldOwnerDamage=QUARANTINED " +
                            "newOwnerReconstruction=NOT_0_5_1",owner.uuid(),owner.dimension(),
                    owner.generation(),reason);
        }catch(IOException|RuntimeException io){
            fault=true;quarantined.add(owner);gateway.fence().orphan(owner);
            log.error("[PDD-PLOT] phase=OWNER_ORPHAN status=IO_FAULT owner={} action=HALT_PLOT_MUTATIONS",owner,io);
        }
    }
    /** Detect only real changed loaded Plot blocks; no global position scan or forced chunks. */
    public void onBlockReplacement(ServerLevel level,BlockPos allocated,BlockState before,BlockState after){
        if(!server.isSameThread()||fault)return;
        try{
            var container=SubLevelContainer.getContainer(level);
            if(container==null)return;
            var plot=container.getPlot(new ChunkPos(allocated));
            if(plot==null||!(plot.getSubLevel() instanceof ServerSubLevel sub)||sub.isRemoved())return;
            var center=plot.getCenterBlock();
            BlockPos rel=allocated.subtract(center);
            String dimension=level.dimension().location().toString();
            // Restored sparse cells must be watched immediately, even before
            // this process sees its first fresh impact and binds a new owner.
            Set<PlotOwnerFence.Owner> candidates=new HashSet<>(activeOwnersByUuid.getOrDefault(sub.getUniqueId(),Set.of()));
            var boundOwner=gateway.fence().owner(dimension,sub.getUniqueId());
            if(boundOwner!=null)candidates.add(boundOwner);
            candidates.addAll(restoredOwners.getOrDefault(sub.getUniqueId(),Set.of()));
            for(var owner:candidates){
                if(!owner.dimension().equals(dimension)||!owner.uuid().equals(sub.getUniqueId()))continue;
                var sample=PlotKeyCodec.storage(new SparseStateKey(dimension,"PLOT",owner.uuid().toString(),
                        owner.generation(),rel.getX(),rel.getY(),rel.getZ(),"PDD:REPLACEMENT"));
                Coordinate cell=new Coordinate(sample);
                if(stress!=null&&stressWatched.contains(cell))stress.replaced(sample);
                if(gateway.ownsMoveMutation(sample,before,after))continue;
                if(movement.fences.held(sample))movement.fences.replaced(sample);
                if(ownBreak!=null&&new Coordinate(ownBreak).equals(cell))continue;
                if(!movement.fences.held(sample)&&!stressWatched.contains(cell)&&!lineageActive.contains(cell)
                        &&!lineageQuarantine.containsKey(cell)&&!journal.trackedPosition(cell.dimension(),cell.x(),cell.y(),cell.z()))continue;
                String oldFingerprint=com.physicaldestroydeform.material.CopycatMaterial.fingerprint(before);
                if(before.getBlock()==after.getBlock()&&com.physicaldestroydeform.material.CopycatMaterial.isCopycat(oldFingerprint)){
                    var oldReal=new SparseStateKey(dimension,"PLOT",owner.uuid().toString(),owner.generation(),rel.getX(),rel.getY(),rel.getZ(),oldFingerprint);
                    var newReal=new SparseStateKey(dimension,"PLOT",owner.uuid().toString(),owner.generation(),rel.getX(),rel.getY(),rel.getZ(),com.physicaldestroydeform.material.CopycatMaterial.fingerprint(after));
                    long revision=Math.addExact(journal.revision(cell.dimension(),cell.x(),cell.y(),cell.z()),1);
                    journal.remapCopycatShape(PlotKeyCodec.storage(oldReal),PlotKeyCodec.storage(newReal),PlotIncarnation.token(newReal,center.getX(),center.getY(),center.getZ(),revision));
                    continue;
                }
                long revision=journal.replaceBlock(cell.dimension(),cell.x(),cell.y(),cell.z());
                if(!lineageActive.contains(cell)&&revision>lineageQuarantine.getOrDefault(cell,Long.MAX_VALUE))lineageQuarantine.remove(cell);
                if(!lineageActive.contains(cell)&&!lineageQuarantine.containsKey(cell))unregisterState(sample);
                log.info("[PDD-PLOT] phase=REPLACEMENT owner={} local={} " +
                                "old={} next={} retainedDamage=INVALIDATED",owner.uuid(),rel,before,after);
            }
        }catch(IOException e){
            fault=true;log.error("[PDD-PLOT] phase=REPLACEMENT status=IO_FAULT action=HALT",e);
        }catch(RuntimeException invalid){
            fault=true;log.error("[PDD-PLOT] phase=REPLACEMENT status=UNCERTAIN action=HALT",invalid);
        }
    }
    void beginOwnBreak(SparseStateKey virtual){ownBreak=virtual;}
    void endOwnBreak(){ownBreak=null;}
    boolean isMoveQuarantined(SparseStateKey key){return movement.fences.quarantined(key)
            ||lineageActive.contains(new Coordinate(key))||lineageQuarantine.containsKey(new Coordinate(key));}
    boolean acquireMove(MoveTransactionJournal.BeforeImage b){
        if(!movement.fences.acquire(b.id(),b.source(),b.destinationBefore()))return false;
        registerState(b.source());registerState(b.destinationBefore());return true;
    }
    void releaseMove(MoveTransactionJournal.BeforeImage b){
        movement.fences.release(b.id(),b.source(),b.destinationBefore());
        if(journal.get(b.source())==null&&!isMoveQuarantined(b.source()))unregisterState(b.source());
        if(journal.get(b.destinationAfter().key())==null&&!isMoveQuarantined(b.destinationBefore()))unregisterState(b.destinationBefore());
    }
    void quarantineMove(MoveTransactionJournal.BeforeImage b,String reason){
        movement.fences.quarantine(b.source(),b.destinationBefore());registerState(b.source());registerState(b.destinationBefore());
        log.warn("[PDD-DEFORM] owner=PLOT status=QUARANTINED id={} reason={}",b.id(),reason);
    }
    boolean moveInvalidated(MoveTransactionJournal.BeforeImage b){return movement.fences.invalidated(b.id());}
    long moveRevision(SparseStateKey key){return journal.revision(key.dimension(),key.x(),key.y(),key.z());}
    MoveTransactionExecutor.Settlement settleBlockedMove(MoveTransactionJournal.BeforeImage b){
        var result=core.settleBlockedMove(gateway,b,server.getTickCount(),server.overworld().getGameTime());
        log.info("[PDD-DEFORM-BLOCKED] owner=PLOT id={} result={} candidateQ={} appliedCapQ={} fixed={} receipt={}",b.id(),result,b.blockedCandidateQ(),b.blockedAppliedQ(),b.blockedFixedIncrement(),journal.receipt(b.blockedApplyId()));
        return result;
    }
    public void tick(long tick){
        if(fault||core.ioFault()){
            if(tick-lastSummary>=100){lastSummary=tick;log.error("[PDD-PLOT] phase=SUMMARY status=HALTED reason=IO_FAULT");}
            return;
        }
        refreshLifecycle(tick);
        gateway.pruneSafetyHistory(tick);
        if(fault)return;
        // Bounded sparse-state deletion: owner is blocked from writes as soon as
        // the marker is durable, cleanup does not block the server tick unboundedly.
        for(int i=0;i<mutationCap && !orphanCleanup.isEmpty();i++){
            Coordinate c=orphanCleanup.peekFirst();
            if(lineageActive.contains(c)){orphanCleanup.addLast(orphanCleanup.removeFirst());continue;}
            try {journal.discardAtPosition(c.dimension(),c.x(),c.y(),c.z());
                orphanCleanup.removeFirst();orphanCleanupSet.remove(c);
            }
            catch(IOException e){fault=true;log.error("[PDD-PLOT] phase=ORPHAN_CLEANUP status=IO_FAULT",e);return;}
        }
        int recovery=Math.min(MAX_RECOVERY_PER_TICK,unresolved.size());
        for(int i=0;i<recovery;i++){
            var previous=unresolved.removeFirst();
            if(previous.status()==WorldReceiptJournal.Status.PREPARED){
                var owner=PlotOwnerFence.Owner.of(PlotKeyCodec.real(previous.key()));
                // Restart PREPARED is ambiguous even if the Plot is now gone:
                // do not block boot or force the old plot chunk back into memory.
                orphan(owner,"RESTART_PREPARED_UNCERTAIN");
                if(fault)return;
                // No re-execution. Keep the unresolved receipt for explicit repair.
                continue;
            }
            var result=core.reconcileRestart(previous,null,tick);
            if(result.result()==WorldCommitCore.Outcome.QUARANTINED && result.receipt()==null){
                fault=true;log.error("[PDD-PLOT] phase=RECOVERY status=IO_FAULT id={}",previous.id());return;
            }
        }
        movement.tick(server.overworld().getGameTime(),1);
        if(movement.faulted()){fault=true;log.error("[PDD-DEFORM] owner=PLOT status=JOURNAL_FAULT");return;}
        int count=0;
        for(var result:core.tick(gateway,tick,mutationCap,server.overworld().getGameTime())){
            if(result.result()==WorldCommitCore.Outcome.APPLIED_DAMAGE){damaged++;if(journal.get(result.receipt().key())!=null)registerState(result.receipt().key());}
            if(result.result()==WorldCommitCore.Outcome.APPLIED_BREAK){
                broken++;
                if(result.receipt()!=null)unregisterState(result.receipt().key());
            }
            if(result.result()==WorldCommitCore.Outcome.ABORTED)rejected++;
            if(result.result()==WorldCommitCore.Outcome.QUARANTINED)blocked++;
            if(result.result()==WorldCommitCore.Outcome.DEFERRED)continue;
            if(result.result()==WorldCommitCore.Outcome.APPLIED_BREAK || result.result()==WorldCommitCore.Outcome.QUARANTINED || count++<MAX_LOGS_PER_TICK){
                var r=result.receipt();var state=r==null?null:journal.get(r.key());
                log.info(r!=null&&r.id().startsWith("STRESS:")?"[PDD-STRESS-APPLY] phase=COMMIT serverTick={} result={} reason={} id={} target={} damage={} remainder={} status={} blockMutation={} sableLifecycle=SABLE_NORMAL_HOOK":"[PDD-PLOT] phase=COMMIT serverTick={} result={} reason={} id={} target={} " +
                                "damage={} remainder={} status={} blockMutation={} " +
                                "sableLifecycle=VANILLA_CHANGE_HOOK_REQUIRES_INGAME_VERIFICATION",
                        tick,result.result(),result.reason(),r==null?"NONE":r.id(),
                        r==null?"NONE":PlotKeyCodec.real(r.key()),
                        state==null?0:state.damage(),state==null?0:state.remainder(),
                        r==null?"NONE":r.status(),result.result()==WorldCommitCore.Outcome.APPLIED_BREAK);
            }else suppressedCommitLogs++;
        }
        if(journal.checkpointNeeded() || tick%100==0){
            try{journal.checkpointBatch(WorldPersistentJournal.SAVE_DIRTY_BATCH);}
            catch(IOException error){fault=true;log.error("[PDD-PLOT] phase=CHECKPOINT status=IO_FAULT",error);return;}
        }
        if(tick-lastSummary>=100){
            lastSummary=tick;
            log.info("[PDD-PLOT] phase=SUMMARY accepted={} damaged={} broken={} rejected={} " +
                            "blocked={} splitOrphans={} pending={} orphanCleanup={} persistentStates={} receipts={} " +
                            "walBytes={} fault={} heatMapHolds={} breakSpacingHolds={} " +
                            "breakSpacingTicks={} tickWitnessHolds={} suppressedCommitLogs={}",accepted,damaged,broken,rejected,blocked,splitOrphans,
                    core.queued(),orphanCleanup.size(),journal.size(),journal.receiptCount(),journal.bytes(),
                    fault||core.ioFault(),gateway.heatMapHolds(),gateway.spacingHolds(),gateway.breakSpacingTicks(),gateway.tickWitnessHolds(),suppressedCommitLogs);
        }
    }
    public void attachStress(StressRuntime runtime){
        stress=runtime;
    }
    public int cancelRecoveredArchivedStress(long startupTick,long ttl,
            java.util.function.Predicate<WorldCommitCore.Intent> archived)throws IOException {
        return core.abortRecoveredFutureStress(startupTick,ttl,server.getTickCount(),archived);
    }
    public boolean cancelStressOriginOnRollback(WorldCommitCore.Intent intent,DamageModel.Settings settings,long deadline)throws IOException {
        if(fault||core.ioFault())return false;
        core.abortSealedOnRollback(intent,settings,deadline,server.getTickCount());
        return true;
    }
    public boolean cancelStressOutputOnRollback(StressReservationJournal.Entry entry,StressPropagation.PlannedNode plan,
            WorldCommitCore.Intent intent,int index)throws IOException {
        return !fault&&!core.ioFault()&&StressCommitHandoff.cancelOnRollback(core,entry,plan,intent,server.getTickCount(),index);
    }
    public void watchStress(SparseStateKey key,boolean watch){
        var owner=PlotOwnerFence.Owner.of(PlotKeyCodec.real(key));
        Coordinate cell=new Coordinate(key);
        if(watch){stressWatched.add(cell);restoredOwners.computeIfAbsent(owner.uuid(),k->new HashSet<>()).add(owner);}
        else stressWatched.remove(cell);
    }
    public WorldCommitCore.Observation readStress(SparseStateKey key){
        if(fault||core.ioFault()||!server.isSameThread()||isMoveQuarantined(key)||movement.fences.held(key))return null;
        // Saved graph snapshots contain the exact incarnation used for recovery binding in the origin/output.
        var observed=gateway.readIfLoaded(key);
        if(observed==null||observed.isAir())return observed;
        var permission=gateway.canDamage(key,observed);
        if(permission==WorldCommitCore.Permission.UNKNOWN)return null;
        if(permission!=WorldCommitCore.Permission.ALLOW)
            return new WorldCommitCore.Observation(observed.dimension(),observed.fingerprint(),observed.ownerGeneration(),observed.incarnation(),
                    observed.loaded(),false,observed.hasBlockEntity(),observed.unsafeMultiblock(),true,observed.unsafePhysicsPhase());
        return observed;
    }
    public WorldCommitCore.Observation readRecoveredStress(SparseStateKey key,String expectedIncarnation){
        if(fault||core.ioFault()||!server.isSameThread()||isMoveQuarantined(key)||movement.fences.held(key))return null;
        if(!gateway.bindRecoveredStressRoot(key,expectedIncarnation))return null;
        return readStress(key);
    }
    public boolean hasStressReceipt(WorldCommitCore.Intent intent){
        return !fault&&!core.ioFault()&&StressCommitHandoff.receiptMatches(intent,journal.receipt(intent.applyId()));
    }
    public boolean hasStressReceipt(WorldCommitCore.Intent intent,DamageModel.Settings settings){
        return !fault&&!core.ioFault()&&StressCommitHandoff.receiptMatches(intent,settings,journal.receipt(intent.applyId()));
    }
    public boolean hasStressReceipt(WorldCommitCore.Intent intent,DamageModel.Settings settings,long deadline){
        return !fault&&!core.ioFault()&&StressCommitHandoff.receiptMatches(intent,settings,deadline,journal.receipt(intent.applyId()));
    }
    public boolean recoverStressOrigin(WorldCommitCore.Intent intent,DamageModel.Settings settings,long created,long ttl)throws IOException {
        if(fault||core.ioFault())return false;
        var prior=journal.receipt(intent.applyId());
        if(prior!=null)return StressCommitHandoff.verifyReceipt(intent,settings,prior);
        gateway.readRecoveredIfLoaded(intent); // Bind only against saved owner UUID/epoch/incarnation.
        long deadline=created>Long.MAX_VALUE-ttl?Long.MAX_VALUE:created+ttl;
        var result=core.trySubmitSealed(intent,settings,server.overworld().getGameTime(),deadline);
        if(core.ioFault())throw new IOException("Plot origin recovery journal failure");
        boolean durable=StressCommitHandoff.verifyReceipt(intent,settings,result.receipt());
        if(durable&&result.result()==WorldCommitCore.Outcome.DEFERRED)accepted++;
        if(durable&&result.result()==WorldCommitCore.Outcome.ABORTED){
            rejected++;
            long now=server.getTickCount();
            if(lastSourceLogTick!=now){lastSourceLogTick=now;sourceLogs=0;}
            if(sourceLogs++<MAX_LOGS_PER_TICK)log.warn("[PDD-PLOT] phase=SOURCE_HANDOFF id={} reason={} deadline={} gameTick={}",intent.applyId(),result.reason(),deadline,server.overworld().getGameTime());
            else suppressedCommitLogs++;
        }
        return durable;
    }
    public boolean offerStress(StressReservationJournal.Entry entry,StressPropagation.PlannedNode plan,WorldCommitCore.Intent intent)throws IOException {
        if(fault||core.ioFault())return false;
        return offerStress(entry,plan,intent,entry.plans().indexOf(plan));
    }
    public boolean offerStress(StressReservationJournal.Entry entry,StressPropagation.PlannedNode plan,WorldCommitCore.Intent intent,int index)throws IOException {
        if(fault||core.ioFault())return false;
        var owner=PlotOwnerFence.Owner.of(PlotKeyCodec.real(intent.key()));
        restoredOwners.computeIfAbsent(owner.uuid(),k->new HashSet<>()).add(owner);
        return StressCommitHandoff.offer(core,entry,plan,intent,server.overworld().getGameTime(),index);
    }
    public void stop(){
        gateway.stopLifecycle();
        try{
            if(fault||core.ioFault())log.warn("[PDD-PLOT] phase=STOP checkpointSkipped=IO_FAULT wal=RETAINED");
            else{
            journal.stagePendingOrder(core.pendingSnapshot());
            int n=0;
            do{journal.checkpointBatch(WorldPersistentJournal.SAVE_DIRTY_BATCH);}
            while(journal.checkpointPending()&&++n<4096);
            log.info("[PDD-PLOT] phase=STOP checkpointPending={} retained={} receipts={} pending={}",
                    journal.checkpointPending(),journal.size(),journal.receiptCount(),core.queued());
            }
        }catch(IOException failed){
            log.error("[PDD-PLOT] phase=STOP status=CHECKPOINT_FAILED wal=RETAINED",failed);
        }
        active.clear();activeOwnersByUuid.clear();quarantined.clear();knownSplitParents.clear();unresolved.clear();orphanCleanup.clear();orphanCleanupSet.clear();
        verifiedLineage.clear();lineageActive.clear();lineageQuarantine.clear();
        restoredOwners.clear();
    }
}
