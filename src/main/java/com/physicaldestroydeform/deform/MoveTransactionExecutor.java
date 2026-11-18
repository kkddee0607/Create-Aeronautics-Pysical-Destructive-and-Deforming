package com.physicaldestroydeform.deform;

import com.physicaldestroydeform.world.WorldPersistentJournal;
import com.physicaldestroydeform.world.WorldReceiptJournal;
import java.io.IOException;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Server-thread transaction protocol. Gateways must prove the live geometry,
 * ownership, claims, dependencies and signed target evidence before PREPARED.
 * No default gateway approves mutations. No block/drop API is called here.
 */
public final class MoveTransactionExecutor {
    private static final Logger LOG=LoggerFactory.getLogger(MoveTransactionExecutor.class);
    private static final boolean TRACE=Boolean.parseBoolean(System.getProperty("pdd.deform.trace","true"));
    public enum Gate { APPROVED, BLOCKED, DEFERRED, UNKNOWN }
    public enum Readback { CONFIRMED, WAITING, RESTORED_BEFORE_IMAGE, UNCERTAIN }
    public enum Outcome { APPLIED, BLOCKED, DEFERRED, UNKNOWN, QUARANTINED, DUPLICATE }
    public enum Settlement { SETTLED, WAITING, UNCERTAIN }
    public interface Gateway {
        boolean serverThread();
        /** Installs both-cell mutation/invalidation fences; fails without touching blocks. */
        boolean acquire(MoveTransactionJournal.BeforeImage before);
        Gate validate(MoveTransactionJournal.BeforeImage before);
        /** One normal destination setter, place-event handling, then one source setter.
         * Caller owns WAL. Never manual drops, chunk creation, or replayed setters. */
        Readback mutateOnce(MoveTransactionJournal.BeforeImage before);
        /** Plot CONFIRMED requires a later completed clean HeatMap tick and unchanged owner. */
        Readback readback(MoveTransactionJournal.BeforeImage before);
        void release(MoveTransactionJournal.BeforeImage before);
        /** Persist/retain both cells as unsafe. Must not write blocks while quarantining. */
        void quarantine(MoveTransactionJournal.BeforeImage before,String reason);
        /** Additional damage only from the sealed reserve, with its own durable deterministic receipt.
         * Called after movement fences are released; WAITING must be safe to retry before any receipt. */
        default Settlement settleBlocked(MoveTransactionJournal.BeforeImage before){return Settlement.UNCERTAIN;}
    }
    private final MoveTransactionJournal moves;
    private final WorldPersistentJournal damage;
    private final Map<String,MoveTransactionJournal.BeforeImage> awaiting=new LinkedHashMap<>();
    private final Map<String,MoveTransactionJournal.BeforeImage> blockedAwaiting=new LinkedHashMap<>();
    private boolean fault;
    public MoveTransactionExecutor(MoveTransactionJournal moves,WorldPersistentJournal damage) {
        this.moves=Objects.requireNonNull(moves);this.damage=Objects.requireNonNull(damage);
    }
    public boolean faulted(){return fault||moves.faulted();}
    public boolean pending(){return !awaiting.isEmpty()||!blockedAwaiting.isEmpty();}
    public boolean recorded(String id){return moves.get(id)!=null;}
    public Outcome execute(MoveTransactionJournal.BeforeImage before,Gateway gateway,long gameTick) {
        Objects.requireNonNull(before);Objects.requireNonNull(gateway);
        if(!gateway.serverThread()||faulted())return Outcome.UNKNOWN;
        var prior=moves.get(before.id());
        if(prior!=null)return prior.before().equals(before)?Outcome.DUPLICATE:Outcome.UNKNOWN;
        if(gameTick<before.createdGameTick()||gameTick>before.expiresGameTick())return Outcome.UNKNOWN;
        var direct=damage.receipt(before.directApplyId());
        if(direct==null||direct.status()!=WorldReceiptJournal.Status.APPLIED
                ||!direct.key().equals(before.source())||!sourceUnchanged(before))return Outcome.UNKNOWN;
        if(!gateway.acquire(before))return Outcome.DEFERRED;
        boolean prepared=false;
        try {
            Gate gate=gateway.validate(before);
            if(gate==Gate.BLOCKED&&before.material()!=null){
                moves.block(before);gateway.release(before);
                blockedAwaiting.put(before.id(),before);
                trace(before,"BLOCKED_RESERVE_PENDING");
                return settle(before,gateway,gameTick);
            }
            if(gate!=Gate.APPROVED){gateway.release(before);return switch(gate){
                case BLOCKED->Outcome.BLOCKED;case DEFERRED->Outcome.DEFERRED;default->Outcome.UNKNOWN;};}
            if(!moves.prepare(before)){gateway.release(before);return Outcome.DUPLICATE;}
            prepared=true;
            var source=before.source();
            String binding=WorldPersistentJournal.relocationPreparationReason(source,before.sourceState(),
                    before.destinationAfter(),before.sourceRevision(),before.destinationRevision());
            damage.transition(new WorldReceiptJournal.Receipt(before.id(),source,
                    WorldReceiptJournal.Status.PREPARED,binding,before.destinationAfter().tick()),null);
            // Serialize/capacity-check the final frame while neither cell has changed.
            damage.checkRelocationAdmission(applied(before),before.sourceState(),before.destinationAfter(),
                    before.sourceRevision(),before.destinationRevision());
            trace(before,"PREPARED");
            Gate revalidated=gateway.validate(before);
            if(revalidated==Gate.BLOCKED)return restored(before,gateway,"PRE_MUTATION_BLOCKED",gameTick);
            if(revalidated!=Gate.APPROVED)
                return uncertain(before,gateway,"PRE_MUTATION_REVALIDATION_FAILED");
            Readback result=gateway.mutateOnce(before);
            trace(before,"MUTATION_READBACK_"+result);
            if(result==Readback.CONFIRMED)return applied(before,gateway);
            if(result==Readback.RESTORED_BEFORE_IMAGE)return restored(before,gateway,"BOTH_BEFORE_IMAGES_VERIFIED",gameTick);
            if(result==Readback.WAITING){awaiting.put(before.id(),before);return Outcome.DEFERRED;}
            return uncertain(before,gateway,"MOVE_MUTATION_UNCERTAIN");
        } catch(IOException|RuntimeException|LinkageError failure) {
            if(prepared||recorded(before.id())){fault=true;return uncertain(before,gateway,"MOVE_PREPARED_FAILURE");}
            // Even prepare() failure can mean the record reached disk before directory fsync failed.
            if(moves.faulted()){fault=true;return uncertain(before,gateway,"MOVE_PREPARE_IO_UNCERTAIN");}
            gateway.release(before);return Outcome.UNKNOWN;
        }
    }
    /** Bounded polling, without retrying either world setter. */
    public int tick(Gateway gateway,long gameTick,int budget) {
        if(budget<1)throw new IllegalArgumentException("move tick budget");
        if(!gateway.serverThread()||faulted())return 0;
        int visited=0;
        var previouslyBlocked=List.copyOf(blockedAwaiting.values());
        for(var b:List.copyOf(awaiting.values())) {
            if(visited>=budget)break;visited++;
            try {
                if(gameTick>b.expiresGameTick()||gameTick<b.createdGameTick()){
                    uncertain(b,gateway,"MOVE_POSTSTATE_OR_DEADLINE_UNCERTAIN");
                    if(faulted())break;
                    continue;
                }
                Readback r=gateway.readback(b);
                if(r==Readback.CONFIRMED)applied(b,gateway);
                else if(r==Readback.RESTORED_BEFORE_IMAGE)restored(b,gateway,"DEFERRED_BEFORE_IMAGES_VERIFIED",gameTick);
                else if(r==Readback.UNCERTAIN||gameTick>b.expiresGameTick()||gameTick<b.createdGameTick())
                    uncertain(b,gateway,"MOVE_POSTSTATE_OR_DEADLINE_UNCERTAIN");
                else {awaiting.remove(b.id());awaiting.put(b.id(),b);}
            }catch(IOException|RuntimeException|LinkageError failure){fault=true;uncertain(b,gateway,"MOVE_READBACK_FAILURE");}
            if(faulted())break;
        }
        for(var b:previouslyBlocked){
            if(visited>=budget||faulted())break;visited++;
            try {settle(b,gateway,gameTick);}
            catch(IOException|RuntimeException|LinkageError failure){fault=true;uncertain(b,gateway,"BLOCKED_SETTLEMENT_FAILURE");}
        }
        return visited;
    }
    /** On restart, never reconstruct success from two equal fingerprints or rerun mutation. */
    public void recover(Gateway gateway) {
        if(!gateway.serverThread())throw new IllegalStateException("server thread required");
        for(var entry:moves.snapshot()) {
            // Crash between the two files after a proven rollback: the single-cell
            // ABORTED marker records that neither move setter can be replayed.
            var b=entry.before();
            var receipt=damage.receipt(b.id());
            if(entry.phase()==MoveTransactionJournal.Phase.PREPARED&&receipt!=null
                    &&receipt.key().equals(b.source())&&receipt.status()==WorldReceiptJournal.Status.ABORTED
                    &&Set.of("PRE_MUTATION_BLOCKED","BOTH_BEFORE_IMAGES_VERIFIED","DEFERRED_BEFORE_IMAGES_VERIFIED").contains(receipt.reason())){
                try{
                    if(b.material()!=null){
                        moves.block(b);blockedAwaiting.put(b.id(),b);trace(b,"BLOCKED_RESERVE_RECOVERED");
                    }else moves.finish(b.id(),MoveTransactionJournal.Phase.ROLLED_BACK,receipt.reason());
                }catch(IOException|RuntimeException failure){fault=true;uncertain(b,gateway,"RESTART_ROLLBACK_JOURNAL_FAILURE");}
                continue;
            }
            if(entry.phase()==MoveTransactionJournal.Phase.PREPARED
                    ||entry.phase()==MoveTransactionJournal.Phase.QUARANTINED)
                uncertain(entry.before(),gateway,"RESTART_TWO_CELL_STATE_UNCERTAIN");
            else if(entry.phase()==MoveTransactionJournal.Phase.BLOCKED_PENDING){
                blockedAwaiting.put(entry.before().id(),entry.before());
                trace(entry.before(),"BLOCKED_RESERVE_RECOVERED");
            }
        }
    }
    private static WorldReceiptJournal.Receipt applied(MoveTransactionJournal.BeforeImage b) {
        return new WorldReceiptJournal.Receipt(b.id(),b.source(),WorldReceiptJournal.Status.APPLIED,
                "MOVE_POSTSTATE_CONFIRMED",b.destinationAfter().tick());
    }
    private Outcome applied(MoveTransactionJournal.BeforeImage b,Gateway gateway) throws IOException {
        damage.finalizeRelocation(applied(b),b.sourceState(),b.destinationAfter(),b.sourceRevision(),b.destinationRevision());
        moves.finish(b.id(),MoveTransactionJournal.Phase.APPLIED,"WORLD_AND_SPARSE_POSTSTATE_DURABLE");
        trace(b,"APPLIED");
        awaiting.remove(b.id());gateway.release(b);return Outcome.APPLIED;
    }
    private Outcome restored(MoveTransactionJournal.BeforeImage b,Gateway gateway,String reason,long gameTick) throws IOException {
        damage.transition(new WorldReceiptJournal.Receipt(b.id(),b.source(),WorldReceiptJournal.Status.ABORTED,
                reason,b.destinationAfter().tick()),null);
        if(b.material()!=null)moves.block(b);
        else moves.finish(b.id(),MoveTransactionJournal.Phase.ROLLED_BACK,reason);
        awaiting.remove(b.id());gateway.release(b);
        trace(b,"ROLLED_BACK_"+reason);
        if(b.material()==null)return Outcome.BLOCKED;
        blockedAwaiting.put(b.id(),b);
        return settle(b,gateway,gameTick);
    }
    private boolean sourceUnchanged(MoveTransactionJournal.BeforeImage b){
        var key=b.source();
        return Objects.equals(damage.get(key),b.sourceState())
                &&damage.revision(key.dimension(),key.x(),key.y(),key.z())==b.sourceRevision();
    }
    private Outcome settle(MoveTransactionJournal.BeforeImage b,Gateway gateway,long gameTick) throws IOException {
        var receipt=damage.receipt(b.blockedApplyId());
        if(receipt!=null)return finishReceipt(b,gateway,receipt);
        if(gameTick>b.expiresGameTick()||gameTick<b.createdGameTick())
            return finishBlocked(b,"BLOCKED_RESERVE_EXPIRED_UNUSED");
        var direct=damage.receipt(b.directApplyId());
        if(direct==null||direct.status()!=WorldReceiptJournal.Status.APPLIED||!direct.key().equals(b.source())
                ||!sourceUnchanged(b))return finishBlocked(b,"BLOCKED_RESERVE_SOURCE_CHANGED_UNUSED");
        if(b.blockedFixedIncrement()==0)return finishBlocked(b,"BLOCKED_RESERVE_ZERO_FIXED_UNUSED");
        Settlement result=gateway.settleBlocked(b);
        receipt=damage.receipt(b.blockedApplyId());
        if(receipt!=null)return finishReceipt(b,gateway,receipt);
        if(result==Settlement.WAITING){blockedAwaiting.remove(b.id());blockedAwaiting.put(b.id(),b);return Outcome.DEFERRED;}
        return uncertain(b,gateway,"BLOCKED_SETTLEMENT_WITHOUT_TERMINAL_RECEIPT");
    }
    private Outcome finishReceipt(MoveTransactionJournal.BeforeImage b,Gateway gateway,WorldReceiptJournal.Receipt receipt) throws IOException {
        if(!receipt.key().equals(b.source()))return uncertain(b,gateway,"BLOCKED_RECEIPT_KEY_MISMATCH");
        return switch(receipt.status()){
            case APPLIED->finishBlocked(b,"BLOCKED_RESERVE_SETTLED");
            case ABORTED->finishBlocked(b,"BLOCKED_RESERVE_ABORTED_UNUSED");
            default->uncertain(b,gateway,"BLOCKED_RECEIPT_NONTERMINAL");
        };
    }
    private Outcome finishBlocked(MoveTransactionJournal.BeforeImage b,String reason) throws IOException {
        moves.finish(b.id(),MoveTransactionJournal.Phase.ROLLED_BACK,reason);
        blockedAwaiting.remove(b.id());
        trace(b,reason);
        return Outcome.BLOCKED;
    }
    private static void trace(MoveTransactionJournal.BeforeImage b,String result){
        if(TRACE)LOG.info("[PDD-MOVE] id={} source={} destination={} result={} epoch={} reservedQ={} candidateQ={} allocatedQ={} shortfallQ={} fixedIncrement={}",
                b.id(),b.source(),b.destinationBefore(),result,b.transformEpoch(),b.reservedQ(),b.blockedCandidateQ(),
                b.blockedAppliedQ(),Math.max(0,b.blockedCandidateQ()-b.blockedAppliedQ()),b.blockedFixedIncrement());
        if(TRACE&&(result.equals("APPLIED")||result.equals("BLOCKED_RESERVE_SETTLED")||result.endsWith("_UNUSED"))){
            double spent=result.equals("BLOCKED_RESERVE_SETTLED")?b.blockedAppliedQ():0;
            LOG.info("[PDD-DEFORM-Q] id={} result={} reservedQ={} spentQ={} unusedQ={}",
                    b.id(),result,b.reservedQ(),b.reservedQ()-spent);
        }
    }
    private Outcome uncertain(MoveTransactionJournal.BeforeImage b,Gateway gateway,String reason) {
        awaiting.remove(b.id());
        blockedAwaiting.remove(b.id());
        try {
            var prior=damage.receipt(b.id());
            if(prior==null||prior.status()==WorldReceiptJournal.Status.PREPARED)
                damage.transition(new WorldReceiptJournal.Receipt(b.id(),b.source(),
                        WorldReceiptJournal.Status.PARTIAL_QUARANTINED,reason,b.destinationAfter().tick()),null);
            var entry=moves.get(b.id());
            if(entry!=null&&(entry.phase()==MoveTransactionJournal.Phase.PREPARED||entry.phase()==MoveTransactionJournal.Phase.BLOCKED_PENDING))
                moves.finish(b.id(),MoveTransactionJournal.Phase.QUARANTINED,reason);
        }catch(IOException|RuntimeException failure){fault=true;}
        try {gateway.quarantine(b,reason);}catch(RuntimeException|LinkageError failure){fault=true;}
        trace(b,"QUARANTINED_"+reason);
        return Outcome.QUARANTINED;
    }
}
