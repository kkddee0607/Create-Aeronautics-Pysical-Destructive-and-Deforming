package com.physicaldestroydeform.world;

import com.physicaldestroydeform.DamageModel;
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
    private static final int MAX_BOOT_RECONCILE_PER_TICK=32, MAX_COMMIT_LOGS_PER_TICK=20;
    private static final boolean VERBOSE=Boolean.getBoolean("pdd.world.verbose")
            ||Boolean.getBoolean("pdd.persistence.verbose");
    private final MinecraftServer server;
    private final Logger log;
    private final WorldPersistentJournal journal;
    private final WorldCommitCore core;
    private final NeoForgeWorldGateway gateway;
    private final Set<Cell> watched=new HashSet<>(), quarantined=new HashSet<>();
    private final Map<Cell,Integer> pendingCells=new HashMap<>();
    private final Deque<WorldReceiptJournal.Receipt> unresolved=new ArrayDeque<>();
    private Cell ownBreak;
    private boolean fault;
    private long lastSummaryTick, rejected, accepted, appliedDamage, appliedBreak, blocked;
    public WorldCommitService(MinecraftServer server,DamageModel.Settings damage,Logger log) throws IOException {
        this.server=Objects.requireNonNull(server);this.log=Objects.requireNonNull(log);
        Path path=server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("pdd-world-0.4.1.wal");
        journal=new WorldPersistentJournal(path.getParent().resolve("pdd-world-0.5.0"),path);
        int limit=Integer.getInteger("pdd.worldBreakCapPerTick",WorldCommitCore.DEFAULT_WORLD_BREAK_CAP_PER_TICK);
        core=new WorldCommitCore(journal,damage,Math.max(1,Math.min(4096,limit)));
        gateway=new NeoForgeWorldGateway(server,this);
        for(var state:journal.stateSnapshot()) {
            Cell cell=new Cell(state.key());watched.add(cell);

        }
        for(var receipt:journal.uncertain()) {
            Cell cell=new Cell(receipt.key());watched.add(cell);
            quarantined.add(cell);unresolved.addLast(receipt);
        }
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
        if(!server.isSameThread() || fault || core.ioFault()){
            rejected++;log.error("[PDD-COMMIT] phase=OFFER reason=SERVER_OR_SAVE_UNSAFE Q={}",grant.issuedQ());return;
        }
        try {
            var projected=WorldIntentMapper.project(grant,materials.generation(),
                    materials::resolveFingerprint,key->{
                        if(quarantined.contains(new Cell(key)))return null;
                        var observed=gateway.readIfLoaded(key);
                        return observed!=null&&observed.loaded()&&!observed.isAir()
                                &&observed.dimension().equals(key.dimension())
                                &&observed.fingerprint().equals(key.fingerprint())
                                ?observed.incarnation():null;
                    });
            int count=0;
            for(var intent:projected.world()) {
                var status=core.submit(intent);
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
        }catch(RuntimeException unexpected){
            rejected++;log.error("[PDD-COMMIT] phase=OFFER reason=INVALID_PLAN credit={} Q={} " +
                    "effect=NOT_COMMITTED",grant.id(),grant.issuedQ(),unexpected);
        }
    }
    public void tick(long tick){
        if(fault || core.ioFault()) {
            if(tick-lastSummaryTick>=100){lastSummaryTick=tick;log.error("[PDD-SAVE] status=HALTED reason=IO_FAULT required=OPERATOR_INTERVENTION");}
            return;
        }
        // Boot recovery is bounded. Unknown PREPARED operations must never re-execute.
        int pending=Math.min(MAX_BOOT_RECONCILE_PER_TICK,unresolved.size());
        for(int i=0;i<pending;i++){
            var receipt=unresolved.removeFirst();
            var observation=gateway.readIfLoaded(receipt.key());
            if(observation==null){unresolved.addLast(receipt);continue;}
            try {
                journal.transition(new WorldReceiptJournal.Receipt(receipt.id(),receipt.key(),
                        WorldReceiptJournal.Status.PARTIAL_QUARANTINED,
                        "RESTART_UNCERTAIN_"+receipt.status()+"_"+(observation.isAir()?"AIR":"PRESENT"),tick),null);
                log.warn("[PDD-SAVE] phase=RECONCILE id={} result=PARTIAL_QUARANTINED " +
                        "prior={} actualAir={} key={} requiresManualRepair=true",
                        receipt.id(),receipt.status(),observation.isAir(),receipt.key());
            }catch(IOException|IllegalArgumentException error){
                fault=true;log.error("[PDD-SAVE] phase=RECONCILE status=IO_FAULT id={}",receipt.id(),error);return;
            }
        }
        gateway.clearPermissionCache();
        int shown=0;
        for(var result:core.tick(gateway,tick)){
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
                log.info("[PDD-COMMIT] phase=RESULT result={} reason={} id={} " +
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
            if(VERBOSE)log.info("[PDD-PERSIST] phase=STORAGE {}",journal.storageStatus());
        }
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
        if(!pendingCells.containsKey(cell) && !quarantined.contains(cell)
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
        if(cell.equals(ownBreak) || (!watched.contains(cell)
                && !journal.trackedPosition(cell.dimension(),cell.x(),cell.y(),cell.z())))return;
        try {
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
        }catch(IOException unexpected) {
            fault=true;log.error("[PDD-SAVE] phase=BLOCK_CHANGE status=IO_FAULT dimension={} pos={} " +
                    "reason=UNABLE_TO_TOMBSTONE damageMayBeStale=true",cell.dimension(),pos,unexpected);
        }
    }
    void beginOwnBreak(SparseStateKey key){ownBreak=new Cell(key);}
    void endOwnBreak(){ownBreak=null;}
    public void stop(){
        WorldBlockMutationTracker.attach(null);
        try {
            // Shutdown attempts a complete checkpoint. An unexpected failure leaves
            // the already-fsynced old WAL recoverable, not a falsified success.
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
        log.info("[PDD-SAVE] phase=STOP status={} pending={} states={} receipts={} bytes={} " +
                        "mode=SHARDED_FSYNC",
                fault||core.ioFault()?"IO_FAULT":"CLEAN",core.queued(),journal.size(),
                journal.receiptCount(),journal.bytes());
        watched.clear();quarantined.clear();pendingCells.clear();unresolved.clear();
    }
}
