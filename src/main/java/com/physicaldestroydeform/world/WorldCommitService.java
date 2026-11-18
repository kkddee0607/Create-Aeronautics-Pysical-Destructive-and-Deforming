package com.physicaldestroydeform.world;

import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.deform.*;
import com.physicaldestroydeform.stress.*;
import com.physicaldestroydeform.collision.ContactCreditLedger;
import com.physicaldestroydeform.material.MaterialRegistry;
import com.physicaldestroydeform.state.SparseStateKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

/** World-only bridge between already approved F/Q and vanilla server block commits. */
public final class WorldCommitService {
    private record Cell(String dimension,int x,int y,int z) {
        Cell(String dim,BlockPos pos){this(dim,pos.getX(),pos.getY(),pos.getZ());}
        Cell(SparseStateKey key){this(key.dimension(),key.x(),key.y(),key.z());}
    }
    private static final int MAX_BOOT_RECONCILE_PER_TICK=8, MAX_COMMIT_LOGS_PER_TICK=20;
    private static final int MAX_RECOVERY_SAMPLES=4;
    private static final boolean TRACE_RECOVERY=Boolean.getBoolean("pdd.persistence.traceRecovery");
    private static final boolean VERBOSE=Boolean.getBoolean("pdd.world.verbose")
            ||Boolean.getBoolean("pdd.persistence.verbose");
    private final MinecraftServer server;
    private final Logger log;
    private final WorldPersistentJournal journal;
    private final WorldCommitCore core;
    private final DamageModel.Settings damageSettings;
    private StressRuntime stress;
    private final Set<Cell> stressWatched=new HashSet<>();
    private final NeoForgeWorldGateway gateway;
    private final MoveRuntime movement;
    private final Set<Cell> watched=new HashSet<>(), quarantined=new HashSet<>();
    private final Map<Cell,Integer> pendingCells=new HashMap<>();
    private final Deque<WorldReceiptJournal.Receipt> unresolved=new ArrayDeque<>();
    private Cell ownBreak;
    private boolean fault;
    private long lastSummaryTick, rejected, accepted, appliedDamage, appliedBreak, blocked;
    private long recoveredDeferredAborted, recoveredPreparedQuarantined, recoveryLoadMisses;
    private int recoverySamples;
    public WorldCommitService(MinecraftServer server,DamageModel.Settings damage,Logger log) throws IOException {
        this.server=Objects.requireNonNull(server);this.log=Objects.requireNonNull(log);this.damageSettings=damage;
        Path path=server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("pdd-world-0.4.1.wal");
        journal=new WorldPersistentJournal(path.getParent().resolve("pdd-world-0.5.0"),path);
        int limit=Integer.getInteger("pdd.worldBreakCapPerTick",WorldCommitCore.DEFAULT_WORLD_BREAK_CAP_PER_TICK);
        core=new WorldCommitCore(journal,damage,Math.max(1,Math.min(4096,limit)));
        gateway=new NeoForgeWorldGateway(server,this);
        movement=new MoveRuntime(path.getParent().resolve("pdd-world-moves-0.6.0"),journal,gateway.movementGateway(),gateway::planMove,log);
        core.setDeformationHandler(movement::afterDamage);
        core.setExecutionHold(i->movement.busy()||(stress!=null&&stress.holds(i)));
        for(var state:journal.stateSnapshot()) {
            Cell cell=new Cell(state.key());watched.add(cell);

        }
        int historicalQuarantined=0,historicalDeferredSafe=0,historicalTrueQuarantine=0;
        for(var receipt:journal.quarantinedSnapshot()){
            if(movement.recorded(receipt.id()))continue;
            historicalQuarantined++;
            if(WorldPersistentJournal.isLegacyUnexecutedDeferred(receipt)){
                // The old code recorded DEFERRED as uncertain even though it never
                // crossed the PREPARED Vanilla-mutation barrier. Preserve its
                // terminal ApplyId but do not block future independent collisions.
                historicalDeferredSafe++;
                continue;
            }
            Cell cell=new Cell(receipt.key());watched.add(cell);quarantined.add(cell);
            historicalTrueQuarantine++;
        }
        int startupDeferred=0,startupPrepared=0,restored=0;
        for(var receipt:journal.uncertain()) {
            if(movement.recorded(receipt.id()))continue;
            if(receipt.pending()!=null){
                Cell cell=new Cell(receipt.key());watched.add(cell);
                pendingCells.merge(cell,1,Integer::sum);
                restored++;
                continue;
            }
            unresolved.addLast(receipt);
            if(receipt.status()==WorldReceiptJournal.Status.PREPARED) {
                Cell cell=new Cell(receipt.key());watched.add(cell);quarantined.add(cell);
                startupPrepared++;
            }else if(receipt.status()==WorldReceiptJournal.Status.DEFERRED)startupDeferred++;
        }
        log.info("[PDD-RECOVERY] phase=HISTORICAL_QUARANTINE total={} " +
                        "provenNeverExecutedDeferred={} trueMutationUncertain={} " +
                        "action=KEEP_RECEIPTS_AND_PROTECT_PREPARED_OR_UNKNOWN",
                historicalQuarantined,historicalDeferredSafe,historicalTrueQuarantine);
        log.info("[PDD-RECOVERY] phase=START legacyDeferred={} prepared={} restoredPending={} " +
                        "policy=DURABLE_DEFERRED_RESUME_LEGACY_ABORT_PREPARED_QUARANTINE " +
                        "intentPayloadPersisted=true forceChunkLoad=false traceEach={}",
                startupDeferred,startupPrepared,restored,TRACE_RECOVERY);
        movement.recover();
        WorldBlockMutationTracker.attach(this);
        log.info("[PDD-WORLD-POLICY] revision=0.4.1-spreadFix blockTypeVeto=OFF " +
                "blockEntities=VETO multiBlock=VETO " +
                "unbreakableMaterials=VETO loadedChunk=REQUIRED " +
                "ownerCAS=REQUIRED breakEventVeto=ON claims=ENFORCED");
        log.info("[PDD-PERSIST] phase=START legacySchema={} newSchema={} " +
                        "legacyMigration={} restoredDamage={} restoredReceipts={} " +
                        "restartUncertain={} storage={} mode=SHARDED_CHECKPOINT_FSYNC",
                WorldReceiptJournal.VERSION,WorldPersistentJournal.SCHEMA,journal.migrated(),
                journal.size(),journal.receiptCount(),unresolved.size(),journal.storageStatus());
        if(journal.repairedTailBytes()>0)log.warn(
                "[PDD-PERSIST] phase=RECOVERY_TAIL_REPAIR truncatedUncommittedBytes={} " +
                "validFramesPreserved=true completeCRCFailure=FAIL_CLOSED",
                journal.repairedTailBytes());
    }
    /** Increment only for an actually watched location; each token also binds exact state fingerprint. */
    public String incarnation(String dimension,BlockPos pos,String fingerprint) {
        Cell cell=new Cell(dimension,pos);
        try {
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(fingerprint.getBytes(StandardCharsets.UTF_8));
            return journal.revision(dimension,pos.getX(),pos.getY(),pos.getZ())
                    +"-"+HexFormat.of().formatHex(hash);
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    /** Called only once for each successful pre-existing ledger Grant; source accounting is unchanged. */
    public void offer(ContactCreditLedger.Grant grant,MaterialRegistry.Snapshot materials) {
        offer(grant,materials,new com.physicaldestroydeform.material.TargetMaterials(server,grant.id().pair().dimension(),materials));
    }
    public void offer(ContactCreditLedger.Grant grant,MaterialRegistry.Snapshot materials,com.physicaldestroydeform.material.TargetMaterials targetMaterials) {
        if(!server.isSameThread() || fault || core.ioFault()){
            rejected++;log.error("[PDD-COMMIT] phase=OFFER reason=SERVER_OR_SAVE_UNSAFE Q={}",grant.issuedQ());return;
        }
        try {
            var projected=WorldIntentMapper.projectTargets(grant,materials.generation(),
                    targetMaterials::resolve,key->{
                        if(isQuarantined(key))return null;
                        var observed=gateway.readIfLoaded(key);
                        return observed!=null&&observed.loaded()&&!observed.isAir()
                                &&observed.dimension().equals(key.dimension())
                                &&observed.fingerprint().equals(key.fingerprint())
                                ?observed.incarnation():null;
                    },targetMaterials::direction);
            if(stress!=null)stress.reserve(projected.world(),materials,damageSettings);
            int count=0;
            for(var intent:MoveRuntime.reverseOrder(projected.world())) {
                var status=core.submit(intent,server.getTickCount(),server.overworld().getGameTime());
                if(status.result()==WorldCommitCore.Outcome.DEFERRED) {
                    Cell cell=new Cell(intent.key());
                    watched.add(cell);
                    pendingCells.merge(cell,1,Integer::sum);
                    accepted++;count++;
                } else {
                    rejected++;
                    log.warn("[PDD-COMMIT] phase=QUEUE id={} result={} reason={} key={}",
                            intent.applyId(),status.result(),status.reason(),intent.key());
                }
            }
            if(VERBOSE && (count>0 || projected.missingIncarnationQ()>0 || projected.rejectedQ()>0))
                log.info("[PDD-COMMIT] phase=OFFER credit={} materialGeneration={} worldIntents={} " +
                                "QWorld={} QPlotDeferred={} QMissingMaterial={} QInvalidOwner={} " +
                                "QMissingWorldOrQuarantine={} QUnusedSource={} queue={}",
                        grant.id(),materials.generation(),count,
                        projected.world().stream().mapToDouble(WorldCommitCore.Intent::sealedQ).sum(),
                        projected.plotQ(),projected.missingMaterialQ(),projected.rejectedQ(),
                        projected.missingIncarnationQ(),grant.unused(),core.queued());
        }catch(IOException|RuntimeException unexpected){
            rejected++;log.error("[PDD-COMMIT] phase=OFFER reason=INVALID_PLAN credit={} Q={} " +
                    "effect=NOT_COMMITTED",grant.id(),grant.issuedQ(),unexpected);
        }
    }
    public void tick(long tick){
        if(fault || core.ioFault()) {
            if(tick-lastSummaryTick>=100){lastSummaryTick=tick;log.error("[PDD-SAVE] status=HALTED reason=IO_FAULT required=OPERATOR_INTERVENTION");}
            return;
        }
        // Bounded recovery: no physics callback, no forced chunk load, no Q reissue.
        // Only legacy payload-less DEFERRED and ambiguous PREPARED remain here.
        // Fully sealed DEFERRED is already in the bounded core queue.
        int recoveryBudget=Math.min(MAX_BOOT_RECONCILE_PER_TICK,unresolved.size());
        long recoveryStarted=System.nanoTime();
        for(int i=0;i<recoveryBudget;i++){
            // Each terminal Receipt is individually fsynced. Soft-budget the
            // total recovery pass so a large old queue cannot stall a tick.
            if(i>0 && System.nanoTime()-recoveryStarted>=2_000_000L)break;
            var prior=unresolved.removeFirst();
            var observation=prior.status()==WorldReceiptJournal.Status.PREPARED
                    ?gateway.readIfLoaded(prior.key()):null;
            var result=core.reconcileRestart(prior,observation,tick);
            if(core.ioFault() || result.receipt()==null && result.result()==WorldCommitCore.Outcome.QUARANTINED){
                fault=true;
                log.error("[PDD-RECOVERY] phase=RECONCILE status=IO_FAULT id={} reason={} " +
                        "action=HALT_WORLD_COMMIT",prior.id(),result.reason());
                return;
            }
            if(result.result()==WorldCommitCore.Outcome.DEFERRED) {
                recoveryLoadMisses++;
                unresolved.addLast(prior);
                continue;
            }
            if(result.result()==WorldCommitCore.Outcome.ABORTED)recoveredDeferredAborted++;
            else if(result.result()==WorldCommitCore.Outcome.QUARANTINED)recoveredPreparedQuarantined++;
            if(TRACE_RECOVERY || recoverySamples<MAX_RECOVERY_SAMPLES){
                recoverySamples++;
                String format="[PDD-RECOVERY] phase=RESOLVE id={} prior={} result={} reason={} " +
                        "actualLoaded={} actualAir={} observedFingerprint={} key={} " +
                        "requiresManualRepair={} unappliedQ=UNKNOWN";
                Object[] fields={prior.id(),prior.status(),result.result(),result.reason(),
                        observation!=null&&observation.loaded(),observation!=null&&observation.isAir(),
                        observation==null?"UNAVAILABLE":observation.fingerprint(),prior.key(),
                        result.result()==WorldCommitCore.Outcome.QUARANTINED};
                if(result.result()==WorldCommitCore.Outcome.QUARANTINED)log.warn(format,fields);
                else log.info(format,fields);
            }
        }
        gateway.clearPermissionCache();
        movement.tick(server.overworld().getGameTime(),1);
        if(movement.faulted()){fault=true;log.error("[PDD-DEFORM] owner=WORLD status=JOURNAL_FAULT");return;}
        int shown=0;
        for(var result:core.tickAtGameTime(gateway,tick,server.overworld().getGameTime())){
            if(result.result()!=WorldCommitCore.Outcome.DEFERRED && result.receipt()!=null)
                releasePending(new Cell(result.receipt().key()));
            switch(result.result()) {
                case APPLIED_DAMAGE -> appliedDamage++;
                case APPLIED_BREAK -> appliedBreak++;
                case QUARANTINED -> blocked++;
                case ABORTED -> rejected++;
                default -> { }
            }
            if(shown++<MAX_COMMIT_LOGS_PER_TICK && result.result()!=WorldCommitCore.Outcome.DEFERRED){
                var receipt=result.receipt();
                var damage=receipt==null?null:journal.get(receipt.key());
                log.info(receipt!=null&&receipt.id().startsWith("STRESS:")?"[PDD-STRESS-APPLY] phase=RESULT result={} reason={} id={} key={} damageU16={} remainderU8={} materialGeneration={} receiptStatus={} walBytes={}":"[PDD-COMMIT] phase=RESULT result={} reason={} id={} " +
                                "key={} damageU16={} remainderU8={} materialGeneration={} " +
                                "receiptStatus={} walBytes={}",
                        result.result(),result.reason(),receipt==null?"NONE":receipt.id(),
                        receipt==null?"NONE":receipt.key(),damage==null?0:damage.damage(),
                        damage==null?0:damage.remainder(),damage==null?"NONE":damage.materialGeneration(),
                        receipt==null?"NONE":receipt.status(),journal.bytes());
            }
        }
        // Recovery WAL transitions are already fsynced. Snapshot I/O has a bounded
        // per-tick budget, and never runs inside a Sable collision callback.
        if(journal.checkpointNeeded() || tick%100==0){
            try {
                var before=journal.stats();
                int flushed=journal.checkpointBatch(WorldPersistentJournal.SAVE_DIRTY_BATCH);
                var after=journal.stats();
                if(flushed>0 && (VERBOSE || before.generation()!=after.generation()))
                    log.info("[PDD-PERSIST] phase=CHECKPOINT flushedShards={} " +
                                    "generationBefore={} generationAfter={} pending={} state={} receipt={} walBytes={}",
                            flushed,before.generation(),after.generation(),after.checkpointing(),
                            after.states(),after.receipts(),after.walBytes());
            }catch(IOException checkpointFailure){
                fault=true;
                log.error("[PDD-PERSIST] phase=CHECKPOINT status=IO_FAULT " +
                        "action=HALT_WORLD_COMMIT walPreserved=true",checkpointFailure);
                return;
            }
        }
        if(tick-lastSummaryTick>=100) {
            lastSummaryTick=tick;
            log.info("[PDD-SAVE] phase=SUMMARY accepted={} appliedDamage={} appliedBreak={} " +
                            "rejected={} quarantined={} queued={} restorePending={} retainedDamage={} " +
                            "receipts={} walBytes={} fault={}",
                    accepted,appliedDamage,appliedBreak,rejected,blocked,core.queued(),unresolved.size(),
                    journal.size(),journal.receiptCount(),journal.bytes(),fault||core.ioFault());
            if(recoveredDeferredAborted>0 || recoveredPreparedQuarantined>0 || !unresolved.isEmpty())
                log.info("[PDD-RECOVERY] phase=SUMMARY deferredAborted={} " +
                                "preparedQuarantined={} awaitingLoaded={} loadMissAttempts={} " +
                                "manualRepairOnlyForPrepared=true",
                        recoveredDeferredAborted,recoveredPreparedQuarantined,
                        unresolved.size(),recoveryLoadMisses);
            if(VERBOSE)log.info("[PDD-PERSIST] phase=STORAGE {}",journal.storageStatus());
        }
    }
    boolean isQuarantined(SparseStateKey key){return quarantined.contains(new Cell(key))||movement.fences.quarantined(key);}
    boolean acquireMove(MoveTransactionJournal.BeforeImage b){
        if(isQuarantined(b.source())||isQuarantined(b.destinationBefore())||!movement.fences.acquire(b.id(),b.source(),b.destinationBefore()))return false;
        watched.add(new Cell(b.source()));watched.add(new Cell(b.destinationBefore()));return true;
    }
    void releaseMove(MoveTransactionJournal.BeforeImage b){
        movement.fences.release(b.id(),b.source(),b.destinationBefore());
        releaseIfIdle(new Cell(b.source()));releaseIfIdle(new Cell(b.destinationBefore()));
    }
    void quarantineMove(MoveTransactionJournal.BeforeImage b,String reason){
        movement.fences.quarantine(b.source(),b.destinationBefore());quarantined.add(new Cell(b.source()));quarantined.add(new Cell(b.destinationBefore()));
        watched.add(new Cell(b.source()));watched.add(new Cell(b.destinationBefore()));
        log.warn("[PDD-DEFORM] owner=WORLD status=QUARANTINED id={} reason={}",b.id(),reason);
    }
    boolean moveInvalidated(MoveTransactionJournal.BeforeImage b){return movement.fences.invalidated(b.id());}
    long moveRevision(SparseStateKey key){return journal.revision(key.dimension(),key.x(),key.y(),key.z());}
    MoveTransactionExecutor.Settlement settleBlockedMove(MoveTransactionJournal.BeforeImage b){
        var result=core.settleBlockedMove(gateway,b,server.getTickCount(),server.overworld().getGameTime());
        log.info("[PDD-DEFORM-BLOCKED] owner=WORLD id={} result={} candidateQ={} appliedCapQ={} fixed={} receipt={}",b.id(),result,b.blockedCandidateQ(),b.blockedAppliedQ(),b.blockedFixedIncrement(),journal.receipt(b.blockedApplyId()));
        return result;
    }
    private void releasePending(Cell cell) {
        Integer pending=pendingCells.get(cell);
        if(pending!=null) {
            if(pending<=1)pendingCells.remove(cell);
            else pendingCells.put(cell,pending-1);
        }
        releaseIfIdle(cell);
    }
    /** Retain only damaged, pending, or quarantined cells; no lifetime-long watcher leak. */
    private void releaseIfIdle(Cell cell) {
        if(!stressWatched.contains(cell) && !pendingCells.containsKey(cell) && !quarantined.contains(cell)
                && !journal.hasPosition(cell.dimension(),cell.x(),cell.y(),cell.z())) {
            watched.remove(cell);
            // Replacement generations persist independently of in-memory watchers.
        }
    }
    /** Only after a successful block state change (RETURN injection). */
    public void beforeBlockReplacement(Level level,BlockPos pos,
                                       net.minecraft.world.level.block.state.BlockState old,
                                       net.minecraft.world.level.block.state.BlockState next){
        if(!(level instanceof net.minecraft.server.level.ServerLevel serverLevel))return;
        if(!server.isSameThread())return; // Minecraft must enforce this; never access off-thread.
        Cell cell=new Cell(serverLevel.dimension().location().toString(),pos);
        var key=new SparseStateKey(cell.dimension(),"WORLD",cell.dimension(),1,cell.x(),cell.y(),cell.z(),"PDD:REPLACEMENT");
        if(stress!=null&&stressWatched.contains(cell))stress.replaced(key);
        if(gateway.ownsMoveMutation(key,old,next))return;
        if(movement.fences.held(key))movement.fences.replaced(key);
        if(cell.equals(ownBreak) || (!watched.contains(cell)
                && !journal.trackedPosition(cell.dimension(),cell.x(),cell.y(),cell.z())))return;
        try {
            String oldFingerprint=com.physicaldestroydeform.material.CopycatMaterial.fingerprint(old);
            if(old.getBlock()==next.getBlock()&&com.physicaldestroydeform.material.CopycatMaterial.isCopycat(oldFingerprint)){
                String newFingerprint=com.physicaldestroydeform.material.CopycatMaterial.fingerprint(next);
                var before=new SparseStateKey(cell.dimension(),"WORLD",cell.dimension(),1,cell.x(),cell.y(),cell.z(),oldFingerprint);
                var after=new SparseStateKey(cell.dimension(),"WORLD",cell.dimension(),1,cell.x(),cell.y(),cell.z(),newFingerprint);
                long revision=Math.addExact(journal.revision(cell.dimension(),cell.x(),cell.y(),cell.z()),1);
                String token=incarnation(cell.dimension(),pos,newFingerprint);
                journal.remapCopycatShape(before,after,revision+token.substring(token.indexOf('-')));
                releaseIfIdle(cell);return;
            }
            long revision=journal.replaceBlock(cell.dimension(),cell.x(),cell.y(),cell.z());
            // A proven replacement releases the old quarantine on the cell;
            // old ambiguous ApplyIds stay terminal in the receipt ledger.
            quarantined.remove(cell);
            // Durable generation + tombstone share a single WAL frame.
            if(VERBOSE)log.info("[PDD-PERSIST] phase=REPLACEMENT dimension={} " +
                    "x={} y={} z={} incarnationRevision={} damageInvalidated=true",
                    cell.dimension(),cell.x(),cell.y(),cell.z(),revision);
            // Intents already queued will fail their preflight if this block is replaced.
            releaseIfIdle(cell);
        }catch(IOException|RuntimeException unexpected) {
            fault=true;log.error("[PDD-SAVE] phase=BLOCK_CHANGE status=IO_FAULT dimension={} pos={} " +
                    "reason=UNABLE_TO_TOMBSTONE damageMayBeStale=true",cell.dimension(),pos,unexpected);
        }
    }
    void beginOwnBreak(SparseStateKey key){ownBreak=new Cell(key);}
    void endOwnBreak(){ownBreak=null;}
    public void attachStress(StressRuntime runtime){
        stress=runtime;
    }
    public void watchStress(SparseStateKey key,boolean watch){
        Cell cell=new Cell(key);
        if(watch){stressWatched.add(cell);watched.add(cell);}
        else{stressWatched.remove(cell);releaseIfIdle(cell);}
    }
    /** Loaded-only graph preflight uses the same owner/BE/claims safeguards as direct commits. */
    public WorldCommitCore.Observation readStress(SparseStateKey key){
        if(fault||core.ioFault()||!server.isSameThread()||isQuarantined(key)||movement.fences.held(key))return null;
        var observed=gateway.readIfLoaded(key);
        if(observed==null||observed.isAir())return observed;
        var permission=gateway.canDamage(key,observed);
        if(permission==WorldCommitCore.Permission.UNKNOWN)return null;
        if(permission!=WorldCommitCore.Permission.ALLOW)
            return new WorldCommitCore.Observation(observed.dimension(),observed.fingerprint(),observed.ownerGeneration(),observed.incarnation(),
                    observed.loaded(),false,observed.hasBlockEntity(),observed.unsafeMultiblock(),true,observed.unsafePhysicsPhase());
        return observed;
    }
    public boolean recoverStressOrigin(WorldCommitCore.Intent intent,DamageModel.Settings settings,long created,long ttl)throws IOException {
        if(fault||core.ioFault())return false;
        boolean fresh=journal.receipt(intent.applyId())==null;
        long deadline=created>Long.MAX_VALUE-ttl?Long.MAX_VALUE:created+ttl;
        var result=core.submitSealed(intent,settings,server.overworld().getGameTime(),deadline);
        if(core.ioFault())throw new IOException("World origin recovery journal failure");
        if(fresh&&result.result()==WorldCommitCore.Outcome.DEFERRED){
            Cell cell=new Cell(intent.key());watched.add(cell);pendingCells.merge(cell,1,Integer::sum);
        }
        return result.receipt()!=null&&(result.receipt().status()!=WorldReceiptJournal.Status.DEFERRED||result.receipt().pending()!=null);
    }
    public boolean offerStress(StressReservationJournal.Entry entry,StressPropagation.PlannedNode plan,WorldCommitCore.Intent intent)throws IOException {
        if(fault||core.ioFault())return false;
        boolean fresh=journal.receipt(intent.applyId())==null;
        boolean accepted=StressCommitHandoff.offer(core,entry,plan,intent,server.overworld().getGameTime());
        var receipt=journal.receipt(intent.applyId());
        if(accepted&&fresh&&receipt!=null&&receipt.status()==WorldReceiptJournal.Status.DEFERRED){
            Cell cell=new Cell(intent.key());watched.add(cell);pendingCells.merge(cell,1,Integer::sum);
        }
        return accepted;
    }
    public void stop(){
        WorldBlockMutationTracker.attach(null);
        try {
            // Shutdown attempts a complete checkpoint. An unexpected failure leaves
            // the already-fsynced old WAL recoverable, not a falsified success.
            if(!fault&&!core.ioFault())journal.stagePendingOrder(core.pendingSnapshot());
            int passes=0,flushed=0;
            do {
                flushed+=journal.checkpointBatch(WorldPersistentJournal.SAVE_DIRTY_BATCH);
            }while(journal.checkpointPending() && ++passes<4096);
            log.info("[PDD-PERSIST] phase=STOP_CHECKPOINT writtenShards={} pending={} {}",
                    flushed,journal.checkpointPending(),journal.storageStatus());
        }catch(IOException failed){
            fault=true;log.error("[PDD-PERSIST] phase=STOP_CHECKPOINT status=FAILED " +
                    "recoveryWalStillDurable=true",failed);
        }
        log.info("[PDD-RECOVERY] phase=STOP deferredAborted={} " +
                        "preparedQuarantined={} awaitingLoaded={} loadMissAttempts={} " +
                        "requiresManualRepair={}",
                recoveredDeferredAborted,recoveredPreparedQuarantined,unresolved.size(),
                recoveryLoadMisses,recoveredPreparedQuarantined);
        log.info("[PDD-SAVE] phase=STOP status={} pending={} restorePending={} " +
                        "states={} receipts={} bytes={} mode=SHARDED_FSYNC",
                fault||core.ioFault()?"IO_FAULT":"CLEAN",core.queued(),unresolved.size(),
                journal.size(),journal.receiptCount(),journal.bytes());
        watched.clear();quarantined.clear();pendingCells.clear();unresolved.clear();
    }
}
