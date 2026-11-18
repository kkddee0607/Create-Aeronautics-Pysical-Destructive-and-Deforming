package com.physicaldestroydeform.deform;

import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.state.SparseStateKey;
import com.physicaldestroydeform.world.WorldCommitCore;
import com.physicaldestroydeform.world.WorldPersistentJournal;
import com.physicaldestroydeform.world.WorldReceiptJournal;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.slf4j.Logger;

/** One server-thread coordinator per storage domain. Never calls the physics callback. */
public final class MoveRuntime {
    private static final boolean TRACE=Boolean.parseBoolean(System.getProperty("pdd.deform.trace","true"));
    private static final int ROUTINE_TRACE_CAP=com.physicaldestroydeform.diagnostics.TraceBudget.cap(2,16);
    @FunctionalInterface public interface Planner {
        MoveTransactionJournal.BeforeImage build(WorldCommitCore.Intent intent,MovePlan plan,
                WorldReceiptJournal.State state,DamageModel.Settings settings,long gameTick);
    }
    private record Pending(WorldCommitCore.Intent intent,MovePlan plan,WorldReceiptJournal.State state,
                           DamageModel.Settings settings,long deadline){}
    private final MoveTransactionJournal moves;
    private final MoveTransactionExecutor executor;
    private final MoveTransactionExecutor.Gateway gateway;
    private final Planner planner;
    private final Logger log;
    private Pending pending;
    private long logTick=Long.MIN_VALUE;private int logged;
    private long suppressedBelowYield,suppressedUnknown,suppressedBreakFirst,lastTraceSummary;
    public final Fences fences=new Fences();
    public MoveRuntime(Path path,WorldPersistentJournal journal,MoveTransactionExecutor.Gateway gateway,
                       Planner planner,Logger log)throws IOException{
        this.moves=new MoveTransactionJournal(path);this.executor=new MoveTransactionExecutor(moves,journal);
        this.gateway=gateway;this.planner=planner;this.log=log;
    }
    public void recover(){executor.recover(gateway);}
    public boolean recorded(String id){return moves.get(id)!=null;}
    public boolean busy(){return pending!=null||executor.pending();}
    public boolean faulted(){return executor.faulted();}
    public static List<WorldCommitCore.Intent> reverseOrder(List<WorldCommitCore.Intent> intents){
        var list=new ArrayList<>(intents);
        list.sort(Comparator.comparingInt((WorldCommitCore.Intent i)->i.deformationEvidence()==null?-1:i.deformationEvidence().layer())
                .thenComparing(i->i.key().dimension()).thenComparing(i->i.key().ownerId())
                .thenComparingInt(i->i.key().x()).thenComparingInt(i->i.key().y()).thenComparingInt(i->i.key().z())
                .thenComparing(i->i.key().fingerprint()).reversed());
        return List.copyOf(list);
    }
    public void afterDamage(WorldCommitCore.Intent intent,MovePlan plan,WorldReceiptJournal.State state,
                            DamageModel.Settings settings,long gameTick,long deadline){
        if(plan==null)return;
        if(plan.status()!=MovePlan.Status.CANDIDATE_ONLY){
            trace(gameTick,"NO_MOVE_"+plan.status(),plan,0,0);return;
        }
        if(pending!=null||executor.pending())throw new IllegalStateException("move order fence not held");
        pending=new Pending(intent,plan,state,settings,deadline);advance(gameTick);
    }
    public void tick(long gameTick,int budget){
        executor.tick(gateway,gameTick,budget);
        if(!executor.pending()&&!executor.faulted())advance(gameTick);
        if(TRACE&&gameTick-lastTraceSummary>=100){
            lastTraceSummary=gameTick;
            if(suppressedBelowYield+suppressedUnknown+suppressedBreakFirst>0){
                log.info("[PDD-DEFORM] phase=TRACE_SUMMARY gameTick={} suppressedBelowYield={} suppressedUnknown={} suppressedBreakFirst={}",
                        gameTick,suppressedBelowYield,suppressedUnknown,suppressedBreakFirst);
                suppressedBelowYield=suppressedUnknown=suppressedBreakFirst=0;
            }
        }
    }
    private void advance(long tick){
        if(pending==null)return;
        var p=pending;
        if(tick>p.deadline()||executor.faulted()){trace(tick,"MOVE_UNUSED_EXPIRED_OR_FAULT",p.plan(),0,0);pending=null;return;}
        try {
            var before=planner.build(p.intent(),p.plan(),p.state(),p.settings(),tick);
            if(before==null){trace(tick,"MOVE_UNKNOWN_UNUSED",p.plan(),0,0);pending=null;return;}
            before=before.withExpiry(p.deadline());
            // No new TTL on a deferred retry; the coordinator uses the original direct deadline.
            var result=executor.execute(before,gateway,tick);
            trace(tick,result.name(),p.plan(),before.blockedCandidateQ(),before.blockedAppliedQ());
            if(result!=MoveTransactionExecutor.Outcome.DEFERRED||moves.get(before.id())!=null)pending=null;
        }catch(RuntimeException|LinkageError invalid){
            trace(tick,"MOVE_PLAN_REJECTED_UNUSED",p.plan(),0,0);pending=null;
            log.warn("[PDD-DEFORM] phase=PLAN_REJECTED id={} cause={}",p.intent().applyId(),invalid.toString());
        }
    }
    private void trace(long tick,String status,MovePlan p,double candidate,double paid){
        if(!TRACE)return;
        if(logTick!=tick){logTick=tick;logged=0;}
        if(status.startsWith("NO_MOVE_")&&logged++>=ROUTINE_TRACE_CAP){
            switch(p.status()){
                case BELOW_YIELD -> suppressedBelowYield++;
                case DIRECTION_UNKNOWN -> suppressedUnknown++;
                case BREAK_FIRST -> suppressedBreakFirst++;
                default -> { }
            }
            return;
        }
        log.info("[PDD-DEFORM] phase=RESULT gameTick={} status={} id={} source={} steps={} K={} F={} FSource={} reservedQ={} blockedCandidateQ={} reservedCapQ={} shortfall={} normalFixed={} priorFixed={}",
                tick,status,p.creditId(),p.source(),p.steps(),p.k(),p.sourceF(),p.sourceF()>0?"TARGET_EVIDENCE":"UNAVAILABLE",p.reservedQ(),candidate,paid,Math.max(0,candidate-paid),p.eventNormalDamageFixed(),p.damageBeforeFixed());
    }
    /** Coordinate-level lock independent of a block fingerprint. External changes invalidate CAS. */
    public static final class Fences {
        private record Cell(String dimension,String space,String owner,long generation,int x,int y,int z){
            Cell(SparseStateKey k){this(k.dimension(),k.ownerSpace(),k.ownerId(),k.ownerGeneration(),k.x(),k.y(),k.z());}
        }
        private final Map<Cell,String> held=new HashMap<>();
        private final Set<Cell> quarantined=new HashSet<>();
        private final Set<String> invalidated=new HashSet<>();
        public boolean acquire(String id,SparseStateKey a,SparseStateKey b){
            Cell x=new Cell(a),y=new Cell(b);
            if(x.equals(y)||held.containsKey(x)||held.containsKey(y)||quarantined.contains(x)||quarantined.contains(y))return false;
            held.put(x,id);held.put(y,id);return true;
        }
        public boolean held(SparseStateKey key){return held.containsKey(new Cell(key));}
        public boolean quarantined(SparseStateKey key){return quarantined.contains(new Cell(key));}
        public void replaced(SparseStateKey key){String id=held.get(new Cell(key));if(id!=null)invalidated.add(id);}
        public boolean invalidated(String id){return invalidated.contains(id);}
        public void release(String id,SparseStateKey a,SparseStateKey b){
            held.remove(new Cell(a),id);held.remove(new Cell(b),id);invalidated.remove(id);
        }
        public void quarantine(SparseStateKey a,SparseStateKey b){quarantined.add(new Cell(a));quarantined.add(new Cell(b));}
    }
}
