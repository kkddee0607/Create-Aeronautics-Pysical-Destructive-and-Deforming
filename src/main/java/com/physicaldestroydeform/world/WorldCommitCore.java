package com.physicaldestroydeform.world;

import com.physicaldestroydeform.BlockDamageState;
import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.MaterialProperties;
import com.physicaldestroydeform.state.SparseStateKey;
import com.physicaldestroydeform.deform.MovePlan;
import com.physicaldestroydeform.deform.MoveTransactionJournal;
import com.physicaldestroydeform.deform.MoveTransactionExecutor;
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
        CHUNK_UNLOADED, BLOCK_REMAINS, AIR_WITH_BLOCK_ENTITY, CONFIRMED_AIR_MUTATION_WITNESS }
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
                         MaterialProperties material,long materialGeneration,long eventTick,boolean fFirstPreDamped,String materialRevision,
                         com.physicaldestroydeform.deform.MovePlan.Evidence deformationEvidence) {
        public Intent(String applyId,SparseStateKey key,String expectedIncarnation,double sealedQ,MaterialProperties material,long materialGeneration,long eventTick,boolean fFirstPreDamped){
            this(applyId,key,expectedIncarnation,sealedQ,material,materialGeneration,eventTick,fFirstPreDamped,"",null);
        }
        public Intent(String applyId,SparseStateKey key,String expectedIncarnation,double sealedQ,MaterialProperties material,long materialGeneration,long eventTick,boolean fFirstPreDamped,String materialRevision){
            this(applyId,key,expectedIncarnation,sealedQ,material,materialGeneration,eventTick,fFirstPreDamped,materialRevision,null);
        }
        public Intent {
            if(applyId==null||applyId.isBlank()||applyId.length()>1024||key==null
                ||!"WORLD".equals(key.ownerSpace())||expectedIncarnation==null||expectedIncarnation.isBlank()
                ||!Double.isFinite(sealedQ)||sealedQ<0||material==null||materialGeneration<0||eventTick<0
                ||materialRevision==null||materialRevision.length()>128||deformationEvidence!=null&&!fFirstPreDamped)
                throw new IllegalArgumentException("invalid sealed World intent");
        }
    }
    public record Decision(Outcome result,String reason,WorldReceiptJournal.Receipt receipt) {}
    /** WorldGateway must never load a new chunk. It must run all methods on the actual server thread. */
    public interface WorldGateway {
        boolean isServerThread();
        Observation readIfLoaded(SparseStateKey key);
        /** Recovery uses identical live validation; Plot may additionally bind the saved UUID. */
        default Observation readRecoveredIfLoaded(Intent intent) { return readIfLoaded(intent.key()); }
        /** Permanent owner/target quarantine blocks recovered siblings before a world read. */
        default String rejectRecoveredReason(Intent intent) { return null; }
        /** Production gateways verify the sealed material snapshot separately from block identity. */
        default boolean materialMatches(Intent intent) { return true; }
        Permission canDamage(SparseStateKey key,Observation expected);
        Permission canBreak(SparseStateKey key,Observation expected);
        /** Execute at most once. Implementation calls vanilla destroy/drop exactly once; no manual drops. */
        boolean breakVanillaOnce(SparseStateKey key);
        /** Authoritative only when the gateway observed the *same* loaded chunk cell
         * complete its original-state -> AIR mutation during this one break call.
         * Never infer success from API=true or owner removal by itself.
         */
        default boolean witnessedBreakToAir(SparseStateKey key) { return false; }
        /** Plot-only read-only safety gate, before PREPARED. Null means eligible.
         * A deferred intent retains its original durable DEFERRED receipt and Q.
         * World gateways use the default and remain unaffected.
         */
        default String deferBreakReason(SparseStateKey key) { return null; }
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
    private long breakBudgetTick=Long.MIN_VALUE;
    private int breakAttempts;
    private void resetBreakBudget(long tick){if(breakBudgetTick!=tick){breakBudgetTick=tick;breakAttempts=0;}}
    private boolean ioFault;
    private java.util.function.Predicate<Intent> executionHold=intent->false;
    private java.util.function.Predicate<Intent> rollbackHold=intent->false;
    @FunctionalInterface public interface DeformationHandler {
        void afterDamage(Intent intent,MovePlan plan,WorldReceiptJournal.State state,
                         DamageModel.Settings settings,long gameTick,long deadline);
    }
    private DeformationHandler deformation=(a,b,c,d,e,f)->{};
    public synchronized void setDeformationHandler(DeformationHandler handler){deformation=Objects.requireNonNull(handler);}
    /** Server-owned reservations may hold an intent without renewing its deadline. */
    public synchronized void setExecutionHold(java.util.function.Predicate<Intent> hold) {
        executionHold=Objects.requireNonNull(hold);
    }
    /** A marked reservation owns cancellation. Other queue entries may still proceed. */
    public synchronized void setRollbackHold(java.util.function.Predicate<Intent> hold) {
        rollbackHold=Objects.requireNonNull(hold);
    }
    public WorldCommitCore(WorldJournal journal,DamageModel.Settings settings,int breakCapPerTick) {
        this.journal=Objects.requireNonNull(journal);damageSettings=Objects.requireNonNull(settings);
        if(breakCapPerTick<1||breakCapPerTick>4096)throw new IllegalArgumentException("invalid break cap");
        this.breakCapPerTick=breakCapPerTick;
        // Load only never-executed, fully sealed payloads. A fresh source Grant is
        // neither required nor permitted to reissue any of this Q.
        journal.uncertain().stream().filter(r->r.status()==WorldReceiptJournal.Status.DEFERRED && r.pending()!=null)
                .sorted(Comparator.comparingLong(r->r.pending().order())).forEach(r->{
                    if(queue.size()>=MAX_QUEUED||!queuedIds.add(r.id()))
                        throw new IllegalStateException("invalid recovered pending queue");
                    queue.addLast(r.pending().intent());
                });
    }
    /** Fail-closed: if capacity exhausted, reject and report; no silent queue drop. */
    public synchronized Decision submit(Intent intent) {
        return submit(intent,intent.eventTick(),intent.eventTick());
    }
    /** Durable game clock survives a normal world restart; server tick counter does not. */
    public synchronized Decision submit(Intent intent,long serverTick,long gameTick) {
        long age=Math.max(0,serverTick-intent.eventTick());
        long remaining=Math.max(0,MAX_DEFER_TICKS-age);
        long deadline=gameTick>Long.MAX_VALUE-remaining?Long.MAX_VALUE:gameTick+remaining;
        return enqueue(intent,damageSettings,gameTick,deadline,false,age>MAX_DEFER_TICKS);
    }
    /** Accept an already reserved plan without restarting its game-clock TTL or
     * substituting current settings for those sealed with the source reservation.
     * The caller retains the original eventTick in the intent. No new source Q.
     */
    public synchronized Decision submitSealed(Intent intent,DamageModel.Settings settings,
                                               long gameTick,long expiresGameTick) {
        Objects.requireNonNull(intent);Objects.requireNonNull(settings);
        // The sealed source owns its bounded TTL policy. Do not replace it with
        // the direct-intent default or create a fresh deadline at handoff time.
        if(gameTick<0||expiresGameTick<0)
            throw new IllegalArgumentException("invalid sealed game-clock deadline");
        if(!journal.supportsPendingPayload())
            throw new IllegalStateException("sealed handoff requires durable pending payloads");
        return enqueue(intent,settings,gameTick,expiresGameTick,true,gameTick>expiresGameTick);
    }
    /** The stress reservation still durably owns this Q until a receipt is
     * returned. Temporary queue pressure is not a terminal rejection. */
    public synchronized Decision trySubmitSealed(Intent intent,DamageModel.Settings settings,long gameTick,long expiresGameTick){
        Objects.requireNonNull(intent);Objects.requireNonNull(settings);
        if(gameTick<0||expiresGameTick<0||!journal.supportsPendingPayload())
            return submitSealed(intent,settings,gameTick,expiresGameTick);
        if(!ioFault&&gameTick<=expiresGameTick&&queue.size()>=MAX_QUEUED&&journal.receipt(intent.applyId())==null)
            return new Decision(Outcome.DEFERRED,"STRESS_QUEUE_PRESSURE",null);
        return submitSealed(intent,settings,gameTick,expiresGameTick);
    }
    /** Record non-execution for a missing or unprepared ApplyId without admission,
     * world access or a new deadline. Existing PREPARED and terminal results stay authoritative. */
    public synchronized void abortSealedOnRollback(Intent intent,DamageModel.Settings settings,
                                                    long deadline,long tick)throws IOException {
        Objects.requireNonNull(intent);Objects.requireNonNull(settings);
        if(deadline<0||tick<0||!journal.supportsPendingPayload())
            throw new IllegalArgumentException("invalid sealed rollback cancellation");
        if(ioFault)throw new IOException("commit journal fault before clock rollback cancellation");
        var previous=journal.receipt(intent.applyId());
        if(previous!=null){
            if(!previous.id().equals(intent.applyId())||!previous.key().equals(intent.key())){
                ioFault=true;throw new IOException("clock rollback ApplyId aliases another target");
            }
            if(previous.status()!=WorldReceiptJournal.Status.DEFERRED)return;
            var pending=previous.pending();
            if(pending==null||!pending.intent().equals(intent)||!pending.settings().equals(settings)
                    ||pending.expiresGameTick()!=deadline){
                ioFault=true;throw new IOException("clock rollback ApplyId aliases another payload, settings or deadline");
            }
        }
        var result=finish(intent,WorldReceiptJournal.Status.ABORTED,Outcome.ABORTED,
                "STRESS_CLOCK_ROLLBACK_UNUSED",null,tick);
        if(ioFault)throw new IOException("clock rollback cancellation journal failed: "+result.reason());
    }
    /** Legacy compact stress archives retain receipt ownership but omit creation ticks.
     * Production stress uses a fixed TTL, so a later saved deadline proves future creation. */
    public synchronized int abortRecoveredFutureStress(long startupGameTick,long ttl,long tick,
            java.util.function.Predicate<Intent> archived)throws IOException {
        if(startupGameTick<0||ttl<1||tick<0)throw new IllegalArgumentException("invalid startup clock or TTL");
        Objects.requireNonNull(archived);
        long latest=startupGameTick>Long.MAX_VALUE-ttl?Long.MAX_VALUE:startupGameTick+ttl;
        int cancelled=0;
        for(var intent:queue){
            if(!journal.recoveredUncertain(intent.applyId()))continue;
            var receipt=journal.receipt(intent.applyId());
            var pending=receipt==null?null:receipt.pending();
            if(pending==null||pending.expiresGameTick()<=latest||!archived.test(intent))continue;
            abortSealedOnRollback(intent,pending.settings(),pending.expiresGameTick(),tick);
            cancelled++;
        }
        return cancelled;
    }
    private Decision enqueue(Intent intent,DamageModel.Settings settings,long gameTick,
                             long deadline,boolean sealedHandoff,boolean expired) {
        if(ioFault)return new Decision(Outcome.QUARANTINED,"IO_FAULT",null);
        var existing=journal.receipt(intent.applyId());
        if(existing!=null && (existing.status()!=WorldReceiptJournal.Status.DEFERRED
                || existing.pending()!=null
                || journal.recoveredUncertain(intent.applyId())))
            return new Decision(Outcome.DUPLICATE,"EXISTING_"+existing.status(),existing);
        if(queuedIds.contains(intent.applyId()))return new Decision(Outcome.DUPLICATE,"ALREADY_QUEUED",existing);
        if(sealedHandoff && existing!=null && existing.pending()==null)
            return new Decision(Outcome.QUARANTINED,"MISSING_SEALED_PENDING_PAYLOAD",existing);
        if(existing==null && journal.admissionRejectReason()!=null){
            // No more durable Receipt IDs can be created. Halt, do not reuse or
            // evict old IDs to fake success; source Credit is never re-issued.
            ioFault=true;
            return new Decision(Outcome.QUARANTINED,
                    "SAVE_PRESSURE_"+journal.admissionRejectReason(),null);
        }
        if(expired)
            return finish(intent,WorldReceiptJournal.Status.ABORTED,Outcome.ABORTED,
                    "DEFER_TTL_EXPIRED",null,intent.eventTick());
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
            if(existing==null){
                journal.enqueue(intent,settings,deadline);
            }
        } catch(IOException failed){ioFault=true;return new Decision(Outcome.QUARANTINED,"DURABLE_ENQUEUE_FAILED",null);}
        queue.addLast(intent);queuedIds.add(intent.applyId());
        return new Decision(Outcome.DEFERRED,"QUEUED",journal.receipt(intent.applyId()));
    }
    /** At most breakCapPerTick actual break attempts; bounded non-break budget as well. */
    public synchronized List<Decision> tick(WorldGateway world,long tick) {
        return tick(world,tick,Math.max(64,breakCapPerTick));
    }
    public synchronized List<Decision> tickAtGameTime(WorldGateway world,long tick,long gameTick) {
        return tick(world,tick,Math.max(64,breakCapPerTick),gameTick);
    }
    /** Plot 0.5.1 caps ALL journal/mutation operations, not only final breaks. */
    public synchronized List<Decision> tick(WorldGateway world,long tick,int maxOperations) {
        return tick(world,tick,maxOperations,tick);
    }
    public synchronized List<Decision> tick(WorldGateway world,long tick,int maxOperations,long gameTick) {
        if(maxOperations<1||maxOperations>MAX_QUEUED)
            throw new IllegalArgumentException("invalid operation cap");
        if(!world.isServerThread())throw new IllegalStateException("server thread required");
        if(ioFault)return List.of(new Decision(Outcome.QUARANTINED,"IO_FAULT",null));
        var results=new ArrayList<Decision>();
        // One WAL fsync per receipt: bound the entire operation batch, not only breaks.
        resetBreakBudget(tick);
        int operations=0,limit=Math.min(MAX_QUEUED,maxOperations);
        // One visit per entry per tick: a deferred owner must not monopolize
        // an otherwise short queue or be rechecked repeatedly in this tick.
        int candidates=queue.size();
        while(!queue.isEmpty() && candidates-->0 && operations++<limit) {
            Intent intent=queue.removeFirst();queuedIds.remove(intent.applyId());
            // External split quarantine may durably terminalize a queued Plot intent.
            // A terminal receipt must NEVER be re-executed from an older in-memory queue.
            var terminal=journal.receipt(intent.applyId());
            if(terminal!=null && terminal.status()!=WorldReceiptJournal.Status.DEFERRED){
                results.add(new Decision(Outcome.DUPLICATE,"TERMINAL_RECEIPT_"+terminal.status(),terminal));
                continue;
            }
            if(rollbackHold.test(intent)){
                queue.addLast(intent);queuedIds.add(intent.applyId());
                results.add(new Decision(Outcome.DEFERRED,"STRESS_CLOCK_ROLLBACK_HOLD",terminal));
                continue;
            }
            var pending=terminal==null?null:terminal.pending();
            boolean restored=pending!=null && journal.recoveredUncertain(intent.applyId());
            if(restored){
                String veto=world.rejectRecoveredReason(intent);
                if(veto!=null){
                    results.add(finish(intent,WorldReceiptJournal.Status.ABORTED,Outcome.ABORTED,veto,null,tick));
                    continue;
                }
            }
            if(pending!=null?gameTick>pending.expiresGameTick():
                    tick>intent.eventTick() && tick-intent.eventTick()>MAX_DEFER_TICKS){
                results.add(finish(intent,WorldReceiptJournal.Status.ABORTED,Outcome.ABORTED,
                        "DEFER_TTL_EXPIRED",null,tick));
                continue;
            }
            if(executionHold.test(intent)) {
                queue.addFirst(intent);queuedIds.add(intent.applyId());
                results.add(new Decision(Outcome.DEFERRED,"RESERVATION_PLANNING_HOLD",terminal));
                break;
            }
            var observation=restored?world.readRecoveredIfLoaded(intent):world.readIfLoaded(intent.key());
            if(observation==null||!observation.loaded()) {
                if(intent.deformationEvidence()!=null)queue.addFirst(intent);else queue.addLast(intent);
                queuedIds.add(intent.applyId());
                results.add(new Decision(Outcome.DEFERRED,"CHUNK_NOT_LOADED",journal.receipt(intent.applyId())));
                if(intent.deformationEvidence()!=null)break;
                continue;
            }
            if(!world.materialMatches(intent)) {
                results.add(finish(intent,WorldReceiptJournal.Status.ABORTED,Outcome.ABORTED,
                        "MATERIAL_REVISION_CHANGED",null,tick));
                continue;
            }
            // Never let an old intent tombstone newer damage before verifying the
            // saved incarnation against the currently loaded block.
            String invalid=preflight(intent,observation);
            if(invalid!=null){
                results.add(finish(intent,WorldReceiptJournal.Status.ABORTED,Outcome.ABORTED,invalid,null,tick));
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
            var settings=pending!=null?pending.settings():damageSettings;
            DamageModel.Plan plan=intent.fFirstPreDamped()
                    ?DamageModel.planPreDamped(intent.sealedQ(),intent.material(),before,settings)
                    :DamageModel.plan(intent.sealedQ(),intent.material(),before,settings);
            if(plan.thresholdReached() && breakAttempts>=breakCapPerTick) {
                queue.addFirst(intent);queuedIds.add(intent.applyId());
                results.add(new Decision(Outcome.DEFERRED,"BREAK_CAP",journal.receipt(intent.applyId())));
                break;
            }
            if(plan.thresholdReached()) {
                String hold=world.deferBreakReason(intent.key());
                if(hold!=null) {
                    if(intent.deformationEvidence()!=null)queue.addFirst(intent);else queue.addLast(intent);
                    queuedIds.add(intent.applyId());
                    results.add(new Decision(Outcome.DEFERRED,hold,journal.receipt(intent.applyId())));
                    if(intent.deformationEvidence()!=null)break;
                    continue;
                }
            }
            MovePlan move=intent.fFirstPreDamped()?MovePlan.plan(intent.applyId(),intent.key(),intent.material(),
                    (long)before.damage()*256+before.remainder(),plan.fixedIncrement(),
                    intent.deformationEvidence()==null?0:intent.deformationEvidence().sourceF(),
                    plan.plasticQ(),intent.deformationEvidence()==null?null:intent.deformationEvidence().direction()):null;
            Decision result=apply(world,intent,observation,plan,tick);
            if(result.result()==Outcome.DEFERRED && result.reason().startsWith("AFTER_EVENTS_PLOT_GATE_")) {
                // A protection callback may itself change Sable's HeatMap.
                // Defer with the original unprepared ApplyId instead of
                // pretending that Vanilla was invoked or reissuing Q.
                if(intent.deformationEvidence()!=null)queue.addFirst(intent);else queue.addLast(intent);
                queuedIds.add(intent.applyId());
            }
            results.add(result);
            if(result.result()==Outcome.APPLIED_DAMAGE&&move!=null){
                long deadline=pending!=null?pending.expiresGameTick():gameTick;
                deformation.afterDamage(intent,move,journal.get(intent.key()),settings,gameTick,deadline);
            }
            if(ioFault||result.result()==Outcome.DEFERRED&&intent.deformationEvidence()!=null)break;
            if(plan.thresholdReached() && result.result()!=Outcome.DEFERRED)breakAttempts++;
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
    public synchronized List<Intent> pendingSnapshot(){return List.copyOf(queue);}
    public synchronized boolean ioFault(){return ioFault;}
    /** Settle a proven obstruction from the original plastic reservation, without issuing new Q. */
    public synchronized MoveTransactionExecutor.Settlement settleBlockedMove(WorldGateway world,
            MoveTransactionJournal.BeforeImage b,long tick,long gameTick){
        if(!world.isServerThread()||ioFault)return MoveTransactionExecutor.Settlement.UNCERTAIN;
        resetBreakBudget(tick);
        var old=journal.receipt(b.blockedApplyId());
        if(old!=null)return switch(old.status()){
            case APPLIED,ABORTED -> MoveTransactionExecutor.Settlement.SETTLED;
            default -> MoveTransactionExecutor.Settlement.UNCERTAIN;
        };
        var direct=journal.receipt(b.directApplyId());
        if(direct==null||direct.status()!=WorldReceiptJournal.Status.APPLIED||b.material()==null||b.sourceState()==null)
            return MoveTransactionExecutor.Settlement.UNCERTAIN;
        var intent=new Intent(b.blockedApplyId(),b.source(),b.sourceState().incarnation(),b.blockedAppliedQ(),
                b.material(),b.sourceState().materialGeneration(),tick,false,b.materialRevision());
        String abort=null;
        if(gameTick>b.expiresGameTick())abort="BLOCKED_TTL_EXPIRED";
        else if(b.blockedFixedIncrement()==0)abort="BLOCKED_ZERO_FIXED";
        else if(!Objects.equals(journal.get(b.source()),b.sourceState()))abort="BLOCKED_SOURCE_STATE_CHANGED";
        else if(journal instanceof WorldPersistentJournal p&&p.revision(b.source().dimension(),b.source().x(),b.source().y(),b.source().z())!=b.sourceRevision())abort="BLOCKED_SOURCE_REVISION_CHANGED";
        if(abort!=null)return settleDecision(finish(intent,WorldReceiptJournal.Status.ABORTED,Outcome.ABORTED,abort,null,tick));
        var observation=world.readRecoveredIfLoaded(intent);
        if(observation==null||!observation.loaded())return MoveTransactionExecutor.Settlement.WAITING;
        if(!world.materialMatches(intent))return settleDecision(finish(intent,WorldReceiptJournal.Status.ABORTED,Outcome.ABORTED,"BLOCKED_MATERIAL_REVISION_CHANGED",null,tick));
        var before=new BlockDamageState();before.setDamage(b.sourceState().damage());before.setRemainder(b.sourceState().remainder());
        before.addFixedDamage(b.blockedFixedIncrement());
        boolean broken=before.damage()>=b.material().threshold();
        if(broken&&(breakAttempts>=breakCapPerTick||world.deferBreakReason(intent.key())!=null))return MoveTransactionExecutor.Settlement.WAITING;
        var plan=new DamageModel.Plan(DamageModel.Status.PLANNED_NOT_APPLIED,b.blockedAppliedQ(),0,b.blockedAppliedQ(),
                b.blockedAppliedQ(),0,0,0,0,b.blockedFixedIncrement(),before.damage(),before.remainder(),0,broken,"BLOCKED_RESERVED_Q");
        var result=apply(world,intent,observation,plan,tick);
        if(broken&&result.result()!=Outcome.DEFERRED)breakAttempts++;
        return settleDecision(result);
    }
    private static MoveTransactionExecutor.Settlement settleDecision(Decision decision){
        return switch(decision.result()){
            case APPLIED_DAMAGE,APPLIED_BREAK,ABORTED -> MoveTransactionExecutor.Settlement.SETTLED;
            case DEFERRED -> MoveTransactionExecutor.Settlement.WAITING;
            default -> MoveTransactionExecutor.Settlement.UNCERTAIN;
        };
    }
    /**
     * Reconcile durable receipts recovered from a previous process. DEFERRED is only
     * a queued plan: no Vanilla mutation can precede the PREPARED fsync. Only
     * schema-3 payloads are restored; older payload-less receipts are aborted.
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
            if(prior.pending()!=null)
                return new Decision(Outcome.DEFERRED,"DURABLE_PENDING_RESTORED",current);
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
        if(!world.materialMatches(intent))
            return finish(intent,WorldReceiptJournal.Status.ABORTED,Outcome.ABORTED,"MATERIAL_REVISION_CHANGED",null,tick);
        if(!plan.thresholdReached()) {
            if((plan.plannedDamageUnits()>0 || plan.plannedRemainderU8()>0)
                    && !journal.canStore(intent.key()))
                return finish(intent,WorldReceiptJournal.Status.ABORTED,Outcome.ABORTED,
                        "SPARSE_CHUNK_CAP_EXCEEDED",null,tick);
            var next=new WorldReceiptJournal.State(intent.key(),intent.expectedIncarnation(),plan.plannedDamageUnits(),
                    plan.plannedRemainderU8(),intent.materialGeneration(),tick,
                    journal.get(intent.key())==null?0:journal.get(intent.key()).deformation());
            return finish(intent,WorldReceiptJournal.Status.APPLIED,Outcome.APPLIED_DAMAGE,"FIXED_POINT_APPLIED",next,tick);
        }
        if(world.canBreak(intent.key(),original)!=Permission.ALLOW)
            return finish(intent,WorldReceiptJournal.Status.ABORTED,Outcome.ABORTED,"PROTECTION_OR_CANCELLED",null,tick);
        // Recheck after event/claim hook in case another mod changed the block.
        if(!Objects.equals(world.readIfLoaded(intent.key()),original))
            return finish(intent,WorldReceiptJournal.Status.ABORTED,Outcome.ABORTED,"CAS_AFTER_EVENT",null,tick);
        if(!world.materialMatches(intent))
            return finish(intent,WorldReceiptJournal.Status.ABORTED,Outcome.ABORTED,"MATERIAL_REVISION_CHANGED",null,tick);
        String changedLifecycle=world.deferBreakReason(intent.key());
        if(changedLifecycle!=null)
            return new Decision(Outcome.DEFERRED,"AFTER_EVENTS_PLOT_GATE_"+changedLifecycle,
                    journal.receipt(intent.applyId()));
        Decision prepared=finish(intent,WorldReceiptJournal.Status.PREPARED,Outcome.DEFERRED,"PREPARED_DURABLE",null,tick);
        if(prepared.result()!=Outcome.DEFERRED || ioFault)return prepared;
        try {
            boolean apiResult=world.breakVanillaOnce(intent.key());
            Observation reread=world.readIfLoaded(intent.key());
            BreakReadback readback=classifyBreakReadback(apiResult,reread);
            // The Plot may disappear during Sable's synchronous onBlockChange hook.
            // Accept its lost readback ONLY with a matching RETURN-of-setBlockState
            // witness. API=true alone does not prove the intended block changed.
            boolean witnessed=confirmedMutationWitness(world,intent.key());
            if(apiResult && readback==BreakReadback.OWNER_OR_CHUNK_UNAVAILABLE && witnessed)
                readback=BreakReadback.CONFIRMED_AIR_MUTATION_WITNESS;
            if((reread!=null && reread.loaded() && reread.isAir() && !original.isAir())
                    || witnessed){
                // Durable incarnation revision BEFORE APPLIED, also when Sable
                // removes the Plot and makes ordinary readback impossible.
                try {journal.replaceBlock(intent.key().dimension(),intent.key().x(),
                        intent.key().y(),intent.key().z());}
                catch(IOException failed){
                    ioFault=true;
                    return new Decision(Outcome.QUARANTINED,"BREAK_REVISION_FSYNC_FAILED",null);
                }
            }
            // The observer cannot promote uncertain results to APPLIED, even if it fails.
            try {world.onBreakReadback(intent.key(),apiResult,reread,readback);}
            catch(RuntimeException|LinkageError ignored) { /* diagnostic failure is not a block outcome */ }
            if(readback==BreakReadback.CONFIRMED_AIR
                    || readback==BreakReadback.CONFIRMED_AIR_MUTATION_WITNESS)
                return finish(intent,WorldReceiptJournal.Status.APPLIED,Outcome.APPLIED_BREAK,
                        readback==BreakReadback.CONFIRMED_AIR?"WORLD_REREAD_AIR":"PLOT_RETURN_WITNESSED_AIR",
                        new WorldReceiptJournal.State(intent.key(),intent.expectedIncarnation(),0,0,intent.materialGeneration(),tick),tick);
            return finish(intent,WorldReceiptJournal.Status.PARTIAL_QUARANTINED,Outcome.QUARANTINED,
                    "BREAK_RESULT_UNCERTAIN_API_"+apiResult,null,tick);
        }catch(RuntimeException unknown){
            // If the block did change before an exception, invalidate the old
            // incarnation, but NEVER claim a successful break: the API result
            // and drop side effects are now uncertain.
            if(confirmedMutationWitness(world,intent.key())){
                try {journal.replaceBlock(intent.key().dimension(),intent.key().x(),
                        intent.key().y(),intent.key().z());}
                catch(IOException failed){
                    ioFault=true;
                    return new Decision(Outcome.QUARANTINED,"BREAK_EXCEPTION_REVISION_FSYNC_FAILED",null);
                }
            }
            try {world.onBreakException(intent.key(),unknown);}
            catch(RuntimeException|LinkageError ignored) { /* diagnostics must not affect receipt */ }
            return finish(intent,WorldReceiptJournal.Status.PARTIAL_QUARANTINED,Outcome.QUARANTINED,
                    "BREAK_EXCEPTION_"+unknown.getClass().getSimpleName(),null,tick);
        }
    }
    private static boolean confirmedMutationWitness(WorldGateway world,SparseStateKey key) {
        try {return world.witnessedBreakToAir(key);}
        catch(RuntimeException|LinkageError unavailable){return false;}
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
