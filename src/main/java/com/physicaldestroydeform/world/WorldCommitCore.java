package com.physicaldestroydeform.world;

import com.physicaldestroydeform.BlockDamageState;
import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.MaterialProperties;
import com.physicaldestroydeform.state.SparseStateKey;
import java.io.IOException;
import java.util.*;

/** Server-thread, World-only intent executor. Adapted from an already sealed per-block Q.
 * This class deliberately does not access Sable, launch physics impulses or modify Plot.
 * The gateway must implement NeoForge's real protection/event and vanilla-drop contract.
 */
public final class WorldCommitCore {
    public static final int DEFAULT_WORLD_BREAK_CAP_PER_TICK=64, MAX_QUEUED=16_384;
    public static final long MAX_DEFER_TICKS=1200;
    public enum Outcome { APPLIED_DAMAGE, APPLIED_BREAK, DEFERRED, ABORTED, QUARANTINED, DUPLICATE }
    public enum Permission { ALLOW, DENY, UNKNOWN }
    /** Diagnostic classification only; a native/API acknowledgement is not a verified block mutation. */
    public enum BreakReadback { CONFIRMED_AIR, API_REJECTED, OWNER_OR_CHUNK_UNAVAILABLE,
        CHUNK_UNLOADED, BLOCK_REMAINS, AIR_WITH_BLOCK_ENTITY }
    public static BreakReadback classifyBreakReadback(boolean apiResult,Observation reread) {
        if(!apiResult)return BreakReadback.API_REJECTED;
        if(reread==null)return BreakReadback.OWNER_OR_CHUNK_UNAVAILABLE;
        if(!reread.loaded())return BreakReadback.CHUNK_UNLOADED;
        if(!reread.isAir())return BreakReadback.BLOCK_REMAINS;
        return reread.hasBlockEntity()?BreakReadback.AIR_WITH_BLOCK_ENTITY:BreakReadback.CONFIRMED_AIR;
    }
    public record Observation(String dimension,String fingerprint,long ownerGeneration,String incarnation,
                              boolean loaded,boolean isAir,boolean hasBlockEntity,boolean unsafeMultiblock,
                              boolean unbreakable,boolean unsafePhysicsPhase) {
        public Observation {Objects.requireNonNull(dimension);Objects.requireNonNull(fingerprint);}
    }
    public record Intent(String applyId,SparseStateKey key,String expectedIncarnation,double sealedQ,
                         MaterialProperties material,long materialGeneration,long eventTick,boolean fFirstPreDamped) {
        public Intent {
            if(applyId==null||applyId.isBlank()||applyId.length()>1024||key==null
                ||!"WORLD".equals(key.ownerSpace())||expectedIncarnation==null||expectedIncarnation.isBlank()
                ||!Double.isFinite(sealedQ)||sealedQ<0||material==null||materialGeneration<0||eventTick<0)
                throw new IllegalArgumentException("invalid sealed World intent");
        }
    }
    public record Decision(Outcome result,String reason,WorldReceiptJournal.Receipt receipt) {}
    /** WorldGateway must never load a new chunk. It must run all methods on the actual server thread. */
    public interface WorldGateway {
        boolean isServerThread();
        Observation readIfLoaded(SparseStateKey key);
        Permission canDamage(SparseStateKey key,Observation expected);
        Permission canBreak(SparseStateKey key,Observation expected);
        /** Execute at most once. Implementation calls vanilla destroy/drop exactly once; no manual drops. */
        boolean breakVanillaOnce(SparseStateKey key);
        /** Non-authoritative, read-only diagnostic. Must never influence a durable receipt. */
        default void onBreakReadback(SparseStateKey key,boolean apiResult,
                                     Observation reread,BreakReadback status) { }
        default void onBreakException(SparseStateKey key,RuntimeException failure) { }
    }
    private final WorldJournal journal;
    private final DamageModel.Settings damageSettings;
    private final Deque<Intent> queue=new ArrayDeque<>();
    private final HashSet<String> queuedIds=new HashSet<>();
    private final int breakCapPerTick;
    private boolean ioFault;
    public WorldCommitCore(WorldJournal journal,DamageModel.Settings settings,int breakCapPerTick) {
        this.journal=Objects.requireNonNull(journal);damageSettings=Objects.requireNonNull(settings);
        if(breakCapPerTick<1||breakCapPerTick>4096)throw new IllegalArgumentException("invalid break cap");
        this.breakCapPerTick=breakCapPerTick;
    }
    /** Fail-closed: if capacity exhausted, reject and report; no silent queue drop. */
    public synchronized Decision submit(Intent intent) {
        if(ioFault)return new Decision(Outcome.QUARANTINED,"IO_FAULT",null);
        var existing=journal.receipt(intent.applyId());
        if(existing!=null && (existing.status()!=WorldReceiptJournal.Status.DEFERRED
                || journal.recoveredUncertain(intent.applyId())))
            return new Decision(Outcome.DUPLICATE,"EXISTING_"+existing.status(),existing);
        if(queuedIds.contains(intent.applyId()))return new Decision(Outcome.DUPLICATE,"ALREADY_QUEUED",existing);
        if(existing==null && journal.admissionRejectReason()!=null){
            // No more durable Receipt IDs can be created. Halt, do not reuse or
            // evict old IDs to fake success; source Credit is never re-issued.
            ioFault=true;
            return new Decision(Outcome.QUARANTINED,
                    "SAVE_PRESSURE_"+journal.admissionRejectReason(),null);
        }
        if(queue.size()>=MAX_QUEUED){
            // An issued Q must not disappear as a fictitious deferred intent.
            // Durably close this ApplyId as rejected instead of dropping it silently.
            try {
                var refused=new WorldReceiptJournal.Receipt(intent.applyId(),intent.key(),
                        WorldReceiptJournal.Status.ABORTED,"QUEUE_CAP_EXCEEDED",intent.eventTick());
                journal.transition(refused,null);
                return new Decision(Outcome.ABORTED,"QUEUE_CAP_EXCEEDED",refused);
            }catch(IOException failure){
                ioFault=true;return new Decision(Outcome.QUARANTINED,"QUEUE_CAP_JOURNAL_FAILED",null);
            }
        }
        try {
            if(existing==null)journal.transition(new WorldReceiptJournal.Receipt(intent.applyId(),intent.key(),
                    WorldReceiptJournal.Status.DEFERRED,"QUEUED_IN_CURRENT_RUN",intent.eventTick()),null);
        } catch(IOException failed){ioFault=true;return new Decision(Outcome.QUARANTINED,"DURABLE_ENQUEUE_FAILED",null);}
        queue.addLast(intent);queuedIds.add(intent.applyId());
        return new Decision(Outcome.DEFERRED,"QUEUED",journal.receipt(intent.applyId()));
    }
    /** At most breakCapPerTick actual break attempts; bounded non-break budget as well. */
    public synchronized List<Decision> tick(WorldGateway world,long tick) {
        return tick(world,tick,Math.max(64,breakCapPerTick));
    }
    /** Plot 0.5.1 caps ALL journal/mutation operations, not only final breaks. */
    public synchronized List<Decision> tick(WorldGateway world,long tick,int maxOperations) {
        if(maxOperations<1||maxOperations>MAX_QUEUED)
            throw new IllegalArgumentException("invalid operation cap");
        if(!world.isServerThread())throw new IllegalStateException("server thread required");
        if(ioFault)return List.of(new Decision(Outcome.QUARANTINED,"IO_FAULT",null));
        var results=new ArrayList<Decision>();
        // One WAL fsync per receipt: bound the entire operation batch, not only breaks.
        int attemptedBreaks=0,operations=0,limit=Math.min(MAX_QUEUED,maxOperations);
        while(!queue.isEmpty() && operations++<limit) {
            Intent intent=queue.removeFirst();queuedIds.remove(intent.applyId());
            // External split quarantine may durably terminalize a queued Plot intent.
            // A terminal receipt must NEVER be re-executed from an older in-memory queue.
            var terminal=journal.receipt(intent.applyId());
            if(terminal!=null && terminal.status()!=WorldReceiptJournal.Status.DEFERRED){
                results.add(new Decision(Outcome.DUPLICATE,"TERMINAL_RECEIPT_"+terminal.status(),terminal));
                continue;
            }
            if(tick>intent.eventTick() && tick-intent.eventTick()>MAX_DEFER_TICKS){
                results.add(finish(intent,WorldReceiptJournal.Status.ABORTED,Outcome.ABORTED,
                        "DEFER_TTL_EXPIRED",null,tick));
                continue;
            }
            var observation=world.readIfLoaded(intent.key());
            if(observation==null||!observation.loaded()) {
                queue.addLast(intent);queuedIds.add(intent.applyId());
                results.add(new Decision(Outcome.DEFERRED,"CHUNK_NOT_LOADED",journal.receipt(intent.applyId())));
                continue;
            }
            var prior=journal.get(intent.key());
            if(prior==null && journal.hasPosition(intent.key().dimension(),intent.key().x(),
                    intent.key().y(),intent.key().z())){
                // A different fingerprint/owner occupies the same saved position.
                // Never reuse it; the real replacement event must invalidate it.
                results.add(finish(intent,WorldReceiptJournal.Status.ABORTED,Outcome.ABORTED,
                        "STALE_POSITION_STATE_QUARANTINED",null,tick));
                continue;
            }
            BlockDamageState before=new BlockDamageState();
            if(prior!=null && !prior.incarnation().equals(intent.expectedIncarnation())) {
                try {journal.discardStale(prior.key());}
                catch(IOException failure){ioFault=true;results.add(new Decision(Outcome.QUARANTINED,
                        "STALE_STATE_TOMBSTONE_FAILED",null));break;}
                results.add(finish(intent,WorldReceiptJournal.Status.ABORTED,Outcome.ABORTED,
                        "STALE_STORED_INCARNATION",null,tick));
                continue;
            }
            if(prior!=null) {before.setDamage(prior.damage());before.setRemainder(prior.remainder());}
            // Keep definition-level UNBREAKABLE intact for World and Plot.
            // Opening the registry allow-list must never override material immunity.
            DamageModel.Plan plan=intent.fFirstPreDamped()
                    ?DamageModel.planPreDamped(intent.sealedQ(),intent.material(),before,damageSettings)
                    :DamageModel.plan(intent.sealedQ(),intent.material(),before,damageSettings);
            if(plan.thresholdReached() && attemptedBreaks>=breakCapPerTick) {
                queue.addFirst(intent);queuedIds.add(intent.applyId());
                results.add(new Decision(Outcome.DEFERRED,"BREAK_CAP",journal.receipt(intent.applyId())));
                break;
            }
            Decision result=apply(world,intent,observation,plan,tick);
            results.add(result);
            if(plan.thresholdReached() && result.result()!=Outcome.DEFERRED)attemptedBreaks++;
        }
        return List.copyOf(results);
    }
    /** Terminalize an old-owner queue before split/orphan state cleanup, with WAL durability. */
    public synchronized int abortMatching(java.util.function.Predicate<Intent> ownerFilter,
                                          String reason,long tick) {
        Objects.requireNonNull(ownerFilter);
        if(reason==null||reason.isBlank()||reason.length()>512||tick<0)
            throw new IllegalArgumentException("invalid quarantine reason");
        if(ioFault)return 0;
        int closed=0;
        for(var iterator=queue.iterator();iterator.hasNext();) {
            Intent intent=iterator.next();
            if(!ownerFilter.test(intent))continue;
            var previous=journal.receipt(intent.applyId());
            if(previous!=null && previous.status()==WorldReceiptJournal.Status.DEFERRED) {
                try {
                    journal.transition(new WorldReceiptJournal.Receipt(intent.applyId(),intent.key(),
                            WorldReceiptJournal.Status.ABORTED,reason,tick),null);
                } catch(IOException failure) {ioFault=true;break;}
            }
            iterator.remove();queuedIds.remove(intent.applyId());closed++;
        }
        return closed;
    }
    public synchronized int queued(){return queue.size();}
    public synchronized boolean ioFault(){return ioFault;}
    /**
     * Reconcile durable receipts recovered from a previous process. DEFERRED is only
     * a queued plan: no Vanilla mutation can precede the PREPARED fsync. Since the
     * intent's Q/material snapshot is not persisted, it cannot safely be replayed.
     * PREPARED may have executed Vanilla before crashing, so it stays quarantined.
     */
    public synchronized Decision reconcileRestart(WorldReceiptJournal.Receipt prior,
                                                    Observation observation,long tick) {
        if(ioFault)return new Decision(Outcome.QUARANTINED,"IO_FAULT",null);
        if(prior==null)throw new IllegalArgumentException("missing recovery receipt");
        var current=journal.receipt(prior.id());
        if(current==null || !current.key().equals(prior.key())
                || current.status()!=prior.status())
            return new Decision(Outcome.DUPLICATE,"RECOVERY_RECEIPT_ALREADY_CHANGED",current);
        WorldReceiptJournal.Status next;
        Outcome outcome;
        String reason;
        if(prior.status()==WorldReceiptJournal.Status.DEFERRED){
            next=WorldReceiptJournal.Status.ABORTED;
            outcome=Outcome.ABORTED;
            reason="RESTART_DEFERRED_NOT_EXECUTED_INTENT_NOT_PERSISTED";
        }else if(prior.status()==WorldReceiptJournal.Status.PREPARED){
            if(observation==null || !observation.loaded())
                return new Decision(Outcome.DEFERRED,"RECOVERY_AWAIT_LOADED_CHUNK",prior);
            next=WorldReceiptJournal.Status.PARTIAL_QUARANTINED;
            outcome=Outcome.QUARANTINED;
            reason="RESTART_PREPARED_MUTATION_UNCERTAIN_"+(observation.isAir()?"AIR":"PRESENT");
        }else return new Decision(Outcome.DUPLICATE,"RECOVERY_ALREADY_TERMINAL",prior);
        var resolved=new WorldReceiptJournal.Receipt(prior.id(),prior.key(),next,reason,tick);
        try {
            journal.transition(resolved,null);
            return new Decision(outcome,reason,resolved);
        }catch(IOException|IllegalArgumentException failure){
            ioFault=true;
            return new Decision(Outcome.QUARANTINED,
                    "RECOVERY_JOURNAL_WRITE_FAILED_"+failure.getClass().getSimpleName(),null);
        }
    }
    /** Compatibility entry point for focused crash-recovery tests. */
    public synchronized int quarantineInterrupted(WorldGateway world,long tick) {
        if(!world.isServerThread())throw new IllegalStateException("server thread required");
        int resolved=0;
        for(var prior:journal.uncertain()) {
            Observation observed=prior.status()==WorldReceiptJournal.Status.PREPARED
                    ?world.readIfLoaded(prior.key()):null;
            Decision result=reconcileRestart(prior,observed,tick);
            if(result.receipt()!=null && (result.result()==Outcome.ABORTED
                    ||result.result()==Outcome.QUARANTINED))resolved++;
            if(ioFault)break;
        }
        return resolved;
    }
    private Decision apply(WorldGateway world,Intent intent,Observation original,DamageModel.Plan plan,long tick) {
        String invalid=preflight(intent,original);
        if(invalid!=null)return finish(intent,WorldReceiptJournal.Status.ABORTED,Outcome.ABORTED,invalid,null,tick);
        var latest=world.readIfLoaded(intent.key());
        if(latest==null||!latest.equals(original))
            return finish(intent,WorldReceiptJournal.Status.ABORTED,Outcome.ABORTED,"CAS_PRE_RECHECK",null,tick);
        if(world.canDamage(intent.key(),original)!=Permission.ALLOW)
            return finish(intent,WorldReceiptJournal.Status.ABORTED,Outcome.ABORTED,"DAMAGE_PERMISSION_DENIED",null,tick);
        // Event handlers and claim hooks can replace the target even for non-breaking damage.
        // Never persist a damage-only receipt against a block changed during authorization.
        if(!Objects.equals(world.readIfLoaded(intent.key()),original))
            return finish(intent,WorldReceiptJournal.Status.ABORTED,Outcome.ABORTED,"CAS_AFTER_DAMAGE_EVENT",null,tick);
        if(!plan.thresholdReached()) {
            if((plan.plannedDamageUnits()>0 || plan.plannedRemainderU8()>0)
                    && !journal.canStore(intent.key()))
                return finish(intent,WorldReceiptJournal.Status.ABORTED,Outcome.ABORTED,
                        "SPARSE_CHUNK_CAP_EXCEEDED",null,tick);
            var next=new WorldReceiptJournal.State(intent.key(),intent.expectedIncarnation(),plan.plannedDamageUnits(),
                    plan.plannedRemainderU8(),intent.materialGeneration(),tick);
            return finish(intent,WorldReceiptJournal.Status.APPLIED,Outcome.APPLIED_DAMAGE,"FIXED_POINT_APPLIED",next,tick);
        }
        if(world.canBreak(intent.key(),original)!=Permission.ALLOW)
            return finish(intent,WorldReceiptJournal.Status.ABORTED,Outcome.ABORTED,"PROTECTION_OR_CANCELLED",null,tick);
        // Recheck after event/claim hook in case another mod changed the block.
        if(!Objects.equals(world.readIfLoaded(intent.key()),original))
            return finish(intent,WorldReceiptJournal.Status.ABORTED,Outcome.ABORTED,"CAS_AFTER_EVENT",null,tick);
        Decision prepared=finish(intent,WorldReceiptJournal.Status.PREPARED,Outcome.DEFERRED,"PREPARED_DURABLE",null,tick);
        if(prepared.result()!=Outcome.DEFERRED || ioFault)return prepared;
        try {
            boolean apiResult=world.breakVanillaOnce(intent.key());
            Observation reread=world.readIfLoaded(intent.key());
            if(reread!=null && reread.loaded() && reread.isAir() && !original.isAir()){
                // Our own vanilla break suppresses the LevelChunk replacement mixin.
                // Persist the same-state re-placement generation before publishing APPLIED;
                // otherwise a queued older collision could hit a newly placed same block.
                try {journal.replaceBlock(intent.key().dimension(),intent.key().x(),
                        intent.key().y(),intent.key().z());}
                catch(IOException failed){
                    ioFault=true;
                    return new Decision(Outcome.QUARANTINED,"BREAK_REVISION_FSYNC_FAILED",null);
                }
            }
            BreakReadback readback=classifyBreakReadback(apiResult,reread);
            // The observer cannot promote uncertain results to APPLIED, even if it fails.
            try {world.onBreakReadback(intent.key(),apiResult,reread,readback);}
            catch(RuntimeException|LinkageError ignored) { /* diagnostic failure is not a block outcome */ }
            if(readback==BreakReadback.CONFIRMED_AIR)
                return finish(intent,WorldReceiptJournal.Status.APPLIED,Outcome.APPLIED_BREAK,"WORLD_REREAD_AIR",
                        new WorldReceiptJournal.State(intent.key(),intent.expectedIncarnation(),0,0,intent.materialGeneration(),tick),tick);
            return finish(intent,WorldReceiptJournal.Status.PARTIAL_QUARANTINED,Outcome.QUARANTINED,
                    "BREAK_RESULT_UNCERTAIN_API_"+apiResult,null,tick);
        }catch(RuntimeException unknown){
            try {world.onBreakException(intent.key(),unknown);}
            catch(RuntimeException|LinkageError ignored) { /* diagnostics must not affect receipt */ }
            return finish(intent,WorldReceiptJournal.Status.PARTIAL_QUARANTINED,Outcome.QUARANTINED,
                    "BREAK_EXCEPTION_"+unknown.getClass().getSimpleName(),null,tick);
        }
    }
    private static String preflight(Intent intent,Observation read) {
        SparseStateKey k=intent.key();
        if(!read.loaded()||read.isAir())return "EMPTY_OR_UNLOADED";
        if(intent.material().unbreakable() || read.unbreakable())return "UNBREAKABLE";
        if(read.hasBlockEntity() || read.unsafeMultiblock())return "BE_OR_MULTIBLOCK";
        if(!k.dimension().equals(read.dimension()) || k.ownerGeneration()!=read.ownerGeneration())return "OWNER_CHANGED";
        if(!k.fingerprint().equals(read.fingerprint()))return "FINGERPRINT_CHANGED";
        if(!intent.expectedIncarnation().equals(read.incarnation()))return "REPLACED_SAME_STATE";
        if(read.unsafePhysicsPhase())return "PHYSICS_STEPPING";
        return null;
    }
    private Decision finish(Intent intent,WorldReceiptJournal.Status status,Outcome result,String reason,
                            WorldReceiptJournal.State state,long tick) {
        var receipt=new WorldReceiptJournal.Receipt(intent.applyId(),intent.key(),status,reason,tick);
        try {journal.transition(receipt,state);return new Decision(result,reason,receipt);}
        catch(IOException|IllegalArgumentException e){ioFault=true;return new Decision(Outcome.QUARANTINED,"JOURNAL_WRITE_FAILED_"+e.getClass().getSimpleName(),null);}
    }
}
