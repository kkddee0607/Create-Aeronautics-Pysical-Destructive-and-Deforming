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
    }
    private final WorldReceiptJournal journal;
    private final DamageModel.Settings damageSettings;
    private final Deque<Intent> queue=new ArrayDeque<>();
    private final HashSet<String> queuedIds=new HashSet<>();
    private final int breakCapPerTick;
    private boolean ioFault;
    public WorldCommitCore(WorldReceiptJournal journal,DamageModel.Settings settings,int breakCapPerTick) {
        this.journal=Objects.requireNonNull(journal);damageSettings=Objects.requireNonNull(settings);
        if(breakCapPerTick<1||breakCapPerTick>4096)throw new IllegalArgumentException("invalid break cap");
        this.breakCapPerTick=breakCapPerTick;
    }
    /** Fail-closed: if capacity exhausted, reject and report; no silent queue drop. */
    public synchronized Decision submit(Intent intent) {
        if(ioFault)return new Decision(Outcome.QUARANTINED,"IO_FAULT",null);
        var existing=journal.receipt(intent.applyId());
        if(existing!=null && existing.status()!=WorldReceiptJournal.Status.DEFERRED)
            return new Decision(Outcome.DUPLICATE,"EXISTING_"+existing.status(),existing);
        if(queuedIds.contains(intent.applyId()))return new Decision(Outcome.DUPLICATE,"ALREADY_QUEUED",existing);
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
        if(!world.isServerThread())throw new IllegalStateException("server thread required");
        if(ioFault)return List.of(new Decision(Outcome.QUARANTINED,"IO_FAULT",null));
        var results=new ArrayList<Decision>();
        // One WAL fsync per receipt: bound the entire operation batch, not only breaks.
        int attemptedBreaks=0,operations=0,limit=Math.min(MAX_QUEUED,Math.max(64,breakCapPerTick));
        while(!queue.isEmpty() && operations++<limit) {
            Intent intent=queue.removeFirst();queuedIds.remove(intent.applyId());
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
    public synchronized int queued(){return queue.size();}
    public synchronized boolean ioFault(){return ioFault;}
    /** Interruption before receipt confirmation must NOT lead to re-breaking after restart. */
    public synchronized int quarantineInterrupted(WorldGateway world,long tick) {
        if(!world.isServerThread())throw new IllegalStateException("server thread required");
        int reconciled=0;
        for(var prior:journal.uncertain()) {
            var observation=world.readIfLoaded(prior.key());
            if(observation==null||!observation.loaded())continue; // bounded deferred quarantine until accessible
            try {
                journal.transition(new WorldReceiptJournal.Receipt(prior.id(),prior.key(),
                        WorldReceiptJournal.Status.PARTIAL_QUARANTINED,
                        "RESTART_UNCERTAIN_"+prior.status()+"_ACTUAL_"+
                                (observation.isAir()?"AIR":"PRESENT"),tick),null);
                reconciled++;
            } catch(IOException e){ioFault=true;break;}
        }
        return reconciled;
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
            var next=new WorldReceiptJournal.State(intent.key(),intent.expectedIncarnation(),plan.plannedDamageU8(),
                    plan.plannedRemainderU8(),intent.materialGeneration(),tick);
            return finish(intent,WorldReceiptJournal.Status.APPLIED,Outcome.APPLIED_DAMAGE,"FIXED_POINT_APPLIED",next,tick);
        }
        if(intent.material().unbreakable() || original.unbreakable())
            return finish(intent,WorldReceiptJournal.Status.ABORTED,Outcome.ABORTED,"UNBREAKABLE",null,tick);
        if(original.hasBlockEntity()||original.unsafeMultiblock())
            return finish(intent,WorldReceiptJournal.Status.ABORTED,Outcome.ABORTED,"BE_OR_MULTIBLOCK",null,tick);
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
            if(apiResult&&reread!=null&&reread.loaded()&&reread.isAir()&&!reread.hasBlockEntity())
                return finish(intent,WorldReceiptJournal.Status.APPLIED,Outcome.APPLIED_BREAK,"WORLD_REREAD_AIR",
                        new WorldReceiptJournal.State(intent.key(),intent.expectedIncarnation(),0,0,intent.materialGeneration(),tick),tick);
            return finish(intent,WorldReceiptJournal.Status.PARTIAL_QUARANTINED,Outcome.QUARANTINED,
                    "BREAK_RESULT_UNCERTAIN_API_"+apiResult,null,tick);
        }catch(RuntimeException unknown){
            return finish(intent,WorldReceiptJournal.Status.PARTIAL_QUARANTINED,Outcome.QUARANTINED,
                    "BREAK_EXCEPTION_"+unknown.getClass().getSimpleName(),null,tick);
        }
    }
    private static String preflight(Intent intent,Observation read) {
        SparseStateKey k=intent.key();
        if(!read.loaded()||read.isAir())return "EMPTY_OR_UNLOADED";
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
