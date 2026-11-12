package com.physicaldestroydeform.plot;

import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.collision.ContactCreditLedger;
import com.physicaldestroydeform.material.MaterialRegistry;
import com.physicaldestroydeform.state.SparseStateKey;
import com.physicaldestroydeform.world.WorldCommitCore;
import com.physicaldestroydeform.world.WorldPersistentJournal;
import com.physicaldestroydeform.world.WorldReceiptJournal;
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
    private static final int MAX_RECOVERY_PER_TICK=8, MAX_LOGS_PER_TICK=16;
    private record Coordinate(String dimension,int x,int y,int z) {
        Coordinate(SparseStateKey k){this(k.dimension(),k.x(),k.y(),k.z());}
    }
    private final MinecraftServer server;
    private final Logger log;
    private final WorldPersistentJournal journal;
    private final WorldCommitCore core;
    private final NeoForgePlotGateway gateway;
    private final int mutationCap;
    private final Deque<Coordinate> orphanCleanup=new ArrayDeque<>();
    private final Set<Coordinate> orphanCleanupSet=new HashSet<>();
    private final DamageModel.Settings damageSettings;
    private final Deque<WorldReceiptJournal.Receipt> unresolved=new ArrayDeque<>();
    private final Set<PlotOwnerFence.Owner> quarantined=new HashSet<>();
    private final Set<String> knownSplitParents=new HashSet<>();
    private final Map<PlotOwnerFence.Owner,Set<Coordinate>> active=new HashMap<>();
    private SparseStateKey ownBreak;
    private boolean fault;
    private long accepted,rejected,damaged,broken,blocked,splitOrphans,lastSummary;
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
        for(var state:journal.stateSnapshot())registerState(state.key());
        for(var receipt:journal.quarantinedSnapshot()){
            if(receipt.id().startsWith("ORPHAN:1:")) {
                try {
                    var owner=PlotOwnerFence.Owner.of(PlotKeyCodec.real(receipt.key()));
                    quarantined.add(owner);gateway.fence().orphan(owner);
                }catch(RuntimeException malformed){throw new IOException("malformed persistent Plot orphan marker",malformed);}
            }
        }
        for(var receipt:journal.uncertain())unresolved.add(receipt);
        // Power loss after the durable orphan marker but before state tombstones:
        // re-queue cleanup without allowing stale data to become live.
        for(var owner:quarantined){
            for(Coordinate cell:active.getOrDefault(owner,Set.of()))queueOrphanCleanup(cell);
            active.remove(owner);
        }
        // No old sparse damage may become live merely because a new ship uses the same coordinates.
        log.info("[PDD-PLOT] phase=START revision=0.5.1 cap={} BE=VETO_UNSAFE " +
                        "loaded=REQUIRED ownerEpoch=REQUIRED split=ORPHAN_QUARANTINE " +
                        "storedDamage={} receipt={} unresolved={} orphaned={} schema={} " +
                        "massCollider=SABLE_NORMAL_BLOCK_CHANGE_HOOK_NOT_INDEPENDENTLY_VERIFIED",
                cap,journal.size(),journal.receiptCount(),unresolved.size(),quarantined.size(),
                WorldPersistentJournal.SCHEMA);
    }
    private void unregisterState(SparseStateKey virtual){
        if(!PlotKeyCodec.isPlotStorage(virtual))return;
        var owner=PlotOwnerFence.Owner.of(PlotKeyCodec.real(virtual));
        var cells=active.get(owner);
        if(cells!=null){
            cells.remove(new Coordinate(virtual));
            if(cells.isEmpty())active.remove(owner);
        }
    }
    private void registerState(SparseStateKey virtual){
        if(!PlotKeyCodec.isPlotStorage(virtual))return;
        var owner=PlotOwnerFence.Owner.of(PlotKeyCodec.real(virtual));
        active.computeIfAbsent(owner,k->new HashSet<>()).add(new Coordinate(virtual));
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
        if(!server.isSameThread()||fault||core.ioFault()){
            rejected++;log.error("[PDD-PLOT] phase=OFFER reason=SERVER_OR_SAVE_UNSAFE credit={}",grant.id());return;
        }
        try {
            var projection=PlotIntentMapper.project(grant,materials.generation(),
                    materials::resolveFingerprint,original->{
                        var owner=PlotOwnerFence.Owner.of(original);
                        if(quarantined.contains(owner)||!gateway.bindOnOffer(original))return null;
                        var virtual=PlotKeyCodec.storage(original);
                        var observed=gateway.readIfLoaded(virtual);
                        return observed!=null && observed.loaded() && !observed.isAir()
                                && observed.fingerprint().equals(original.fingerprint())
                                ?observed.incarnation():null;
                    });
            for(var intent:projection.plot()){
                // 0.5.1 CAP-DEFORM-PLAN: no direction is promoted from a
                // native normal to a verified two-cell move. Preview ONLY.
                try {
                    var prior=journal.get(intent.key());
                    com.physicaldestroydeform.BlockDamageState before=new com.physicaldestroydeform.BlockDamageState();
                    if(prior!=null && prior.incarnation().equals(intent.expectedIncarnation())){
                        before.setDamage(prior.damage());before.setRemainder(prior.remainder());
                    }
                    var normal=DamageModel.planPreDamped(intent.sealedQ(),intent.material(),before,damageSettings);
                    var candidate=com.physicaldestroydeform.deform.MovePlan.plan(
                            intent.applyId(),PlotKeyCodec.real(intent.key()),intent.material(),
                            (long)before.damage()*256+before.remainder(),normal.fixedIncrement(),
                            0,normal.plasticQ(),null);
                    if(candidate.status()==com.physicaldestroydeform.deform.MovePlan.Status.DIRECTION_UNKNOWN)
                        log.info("[PDD-DEFORM-PLAN] target={} state={} k={} reservedQ={} " +
                                "action=NO_MOVE reason=SIGNED_OWNER_LOCAL_DIRECTION_UNVERIFIED",
                                candidate.source(),candidate.status(),candidate.k(),candidate.reservedQ());
                }catch(RuntimeException invalidPlan){
                    log.warn("[PDD-DEFORM-PLAN] phase=PREVIEW reason=INVALID_CANDIDATE " +
                            "mutation=OFF id={}",intent.applyId(),invalidPlan);
                }
                var decision=core.submit(intent);
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
        }catch(RuntimeException failed){
            rejected++;log.error("[PDD-PLOT] phase=OFFER reason=INVALID_SEALED_PLAN " +
                    "credit={} Q={} action=REJECT_NO_REISSUE",grant.id(),grant.issuedQ(),failed);
        }
    }
    public boolean faulted(){return fault||core.ioFault();}
    public boolean isSplitParent(String dimension,UUID uuid){
        return knownSplitParents.contains(dimension+"|"+uuid);
    }
    /** Post-physics split detection; old-owner ghost state never migrates by matching XYZ. */
    public void quarantineParent(String dimension,UUID uuid){
        if(!server.isSameThread()||fault)return;
        String parentKey=dimension+"|"+uuid;
        if(!knownSplitParents.add(parentKey))return;
        Set<PlotOwnerFence.Owner> known=new HashSet<>(active.keySet());
        known.addAll(gateway.fence().boundSnapshot());
        for(var receipt:unresolved){
            if(PlotKeyCodec.isPlotStorage(receipt.key()))
                known.add(PlotOwnerFence.Owner.of(PlotKeyCodec.real(receipt.key())));
        }
        for(var owner:known){
            if(owner.dimension().equals(dimension)&&owner.uuid().equals(uuid))
                orphan(owner,"SABLE_SPLIT_FROM_PARENT");
        }
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
            active.remove(owner);
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
        if(!server.isSameThread()||fault||gateway.inMutation())return;
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
            Set<PlotOwnerFence.Owner> candidates=new HashSet<>(active.keySet());
            candidates.addAll(gateway.fence().boundSnapshot());
            for(var owner:candidates){
                if(!owner.dimension().equals(dimension)||!owner.uuid().equals(sub.getUniqueId()))continue;
                var sample=PlotKeyCodec.storage(new SparseStateKey(dimension,"PLOT",owner.uuid().toString(),
                        owner.generation(),rel.getX(),rel.getY(),rel.getZ(),"PDD:REPLACEMENT"));
                Coordinate cell=new Coordinate(sample);
                if(!journal.trackedPosition(cell.dimension(),cell.x(),cell.y(),cell.z()))continue;
                journal.replaceBlock(cell.dimension(),cell.x(),cell.y(),cell.z());
                unregisterState(sample);
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
    public void tick(long tick){
        if(fault||core.ioFault()){
            if(tick-lastSummary>=100){lastSummary=tick;log.error("[PDD-PLOT] phase=SUMMARY status=HALTED reason=IO_FAULT");}
            return;
        }
        gateway.detectLifecycle();
        for(var owner:gateway.fence().orphanedSnapshot())orphan(owner,"OWNER_ID_OR_EPOCH_CHANGED");
        if(fault)return;
        // Bounded sparse-state deletion: owner is blocked from writes as soon as
        // the marker is durable, cleanup does not block the server tick unboundedly.
        for(int i=0;i<mutationCap && !orphanCleanup.isEmpty();i++){
            Coordinate c=orphanCleanup.removeFirst();orphanCleanupSet.remove(c);
            try {journal.discardAtPosition(c.dimension(),c.x(),c.y(),c.z());}
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
        int count=0;
        for(var result:core.tick(gateway,tick,mutationCap)){
            if(result.result()==WorldCommitCore.Outcome.APPLIED_DAMAGE){damaged++;registerState(result.receipt().key());}
            if(result.result()==WorldCommitCore.Outcome.APPLIED_BREAK){
                broken++;
                if(result.receipt()!=null)unregisterState(result.receipt().key());
            }
            if(result.result()==WorldCommitCore.Outcome.ABORTED)rejected++;
            if(result.result()==WorldCommitCore.Outcome.QUARANTINED)blocked++;
            if(count++<MAX_LOGS_PER_TICK && result.result()!=WorldCommitCore.Outcome.DEFERRED){
                var r=result.receipt();var state=r==null?null:journal.get(r.key());
                log.info("[PDD-PLOT] phase=COMMIT result={} reason={} id={} target={} " +
                                "damage={} remainder={} status={} blockMutation={} " +
                                "sableLifecycle=VANILLA_CHANGE_HOOK_REQUIRES_INGAME_VERIFICATION",
                        result.result(),result.reason(),r==null?"NONE":r.id(),
                        r==null?"NONE":PlotKeyCodec.real(r.key()),
                        state==null?0:state.damage(),state==null?0:state.remainder(),
                        r==null?"NONE":r.status(),result.result()==WorldCommitCore.Outcome.APPLIED_BREAK);
            }
        }
        if(journal.checkpointNeeded() || tick%100==0){
            try{journal.checkpointBatch(WorldPersistentJournal.SAVE_DIRTY_BATCH);}
            catch(IOException error){fault=true;log.error("[PDD-PLOT] phase=CHECKPOINT status=IO_FAULT",error);return;}
        }
        if(tick-lastSummary>=100){
            lastSummary=tick;
            log.info("[PDD-PLOT] phase=SUMMARY accepted={} damaged={} broken={} rejected={} " +
                            "blocked={} splitOrphans={} pending={} orphanCleanup={} persistentStates={} receipts={} " +
                            "walBytes={} fault={}",accepted,damaged,broken,rejected,blocked,splitOrphans,
                    core.queued(),orphanCleanup.size(),journal.size(),journal.receiptCount(),journal.bytes(),fault||core.ioFault());
        }
    }
    public void stop(){
        try{
            int n=0;
            do{journal.checkpointBatch(WorldPersistentJournal.SAVE_DIRTY_BATCH);}
            while(journal.checkpointPending()&&++n<4096);
            log.info("[PDD-PLOT] phase=STOP checkpointPending={} retained={} receipts={}",
                    journal.checkpointPending(),journal.size(),journal.receiptCount());
        }catch(IOException failed){
            log.error("[PDD-PLOT] phase=STOP status=CHECKPOINT_FAILED wal=RETAINED",failed);
        }
        active.clear();quarantined.clear();knownSplitParents.clear();unresolved.clear();orphanCleanup.clear();orphanCleanupSet.clear();
    }
}
