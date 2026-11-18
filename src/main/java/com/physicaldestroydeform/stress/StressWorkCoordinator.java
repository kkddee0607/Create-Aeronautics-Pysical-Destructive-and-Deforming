package com.physicaldestroydeform.stress;

import java.io.IOException;
import java.util.*;
import static com.physicaldestroydeform.stress.StressReservationJournal.*;
import static com.physicaldestroydeform.stress.StressPropagation.*;

/** Server-wide durable planning/handoff coordinator. The Minecraft adapter must provide
 * loaded-only graph reads and an idempotent durable commit sink. No physics callbacks.
 * Crash before SEALED replans the saved reservation, never reissues its source Q.
 * Crash during handoff redelivers the SAME ApplyIds; sink receipts provide deduplication.
 */
public final class StressWorkCoordinator {
    public static final int MAX_HANDOFFS_PER_TICK=32,MAX_ADMISSIONS_PER_TICK=32;
    public interface Backend {
        Graph graph(Reservation reservation);
        /** Recheck all observed owner/block identities before allowing a plan to be sealed. */
        boolean validate(Reservation reservation,List<PlannedNode> plans);
        /** Runtime adapters validate one target per charged operation, never an entire frontier for free. */
        default boolean validateNode(Reservation reservation,PlannedNode plan){return validate(reservation,List.of(plan));}
        /** True only when this ApplyId is durably queued or already terminal. Never means APPLIED. */
        boolean enqueue(String applyId,Reservation reservation,PlannedNode plan)throws IOException;
    }
    public record TickResult(int admitted,int plannedOperations,int sealed,int cancelled,int handedOff,int closed) {}
    private record Delivery(String id,int index) {}
    private final StressReservationJournal journal;
    private final StressScheduler scheduler=new StressScheduler();
    private final Backend backend;
    private final Deque<String> waiting=new ArrayDeque<>();
    private final Deque<Delivery> handoffs=new ArrayDeque<>();
    private long lastTick=-1;
    private boolean faulted;
    private final Map<String,Integer> validationCursor=new HashMap<>();
    public StressWorkCoordinator(StressReservationJournal journal,Backend backend){
        this.journal=Objects.requireNonNull(journal);this.backend=Objects.requireNonNull(backend);
        for(var e:journal.unfinished()){
            if(e.phase()==Phase.RESERVED)waiting.addLast(e.reservation().creditId());
            else handoffs.addLast(new Delivery(e.reservation().creditId(),0));
        }
    }
    public boolean submit(Reservation reservation)throws IOException{
        ensureHealthy();boolean added=journal.reserve(reservation);
        if(added)waiting.addLast(reservation.creditId());return added;
    }
    public TickResult tick(long gameTick)throws IOException{
        ensureHealthy();if(gameTick<0||gameTick<lastTick)throw new IllegalArgumentException("game clock rollback");
        // This also bounds handoff fsyncs when the host accidentally calls tick twice.
        if(gameTick==lastTick)return new TickResult(0,0,0,0,0,0);lastTick=gameTick;
        int admitted=0,sealed=0,cancelled=0,delivered=0,closed=0;
        try {
            for(int count=Math.min(waiting.size(),MAX_ADMISSIONS_PER_TICK);count>0;count--){
                String id=waiting.peekFirst();var r=journal.get(id).reservation();
                if(gameTick<r.createdGameTick()){
                    journal.cancelUnplanned(id,"GAME_CLOCK_ROLLBACK_UNUSED");waiting.removeFirst();cancelled++;continue;
                }
                Graph graph;
                try{graph=Objects.requireNonNull(backend.graph(r));}
                catch(RuntimeException unavailable){journal.cancelUnplanned(id,"GRAPH_ADAPTER_UNAVAILABLE_UNUSED");waiting.removeFirst();cancelled++;continue;}
                var status=scheduler.submit(id,r.resumePlanning(gameTick),graph);
                if(status==StressScheduler.Admission.CAPACITY)break;
                waiting.removeFirst();admitted++;
            }
            int operations=scheduler.tick(gameTick);
            // Bound validation and journal fsync transitions independently of graph work.
            int transitions=0;
            for(var done:scheduler.completed()){
                if(transitions++>=MAX_ADMISSIONS_PER_TICK)break;
                var r=journal.get(done.id()).reservation();boolean valid=done.failure()==null;
                int cursor=validationCursor.getOrDefault(done.id(),0);
                if(valid){
                    var plans=done.session().plans();
                    while(cursor<plans.size()&&operations<StressScheduler.GLOBAL_NODES_PER_TICK){
                        operations++;
                        try{valid=backend.validateNode(r,plans.get(cursor));}
                        catch(RuntimeException changed){valid=false;}
                        if(!valid)break;cursor++;
                    }
                    if(valid&&cursor<plans.size()){validationCursor.put(done.id(),cursor);break;}
                    // Empty plans still require one charged owner/source validation.
                    if(plans.isEmpty()){
                        if(operations>=StressScheduler.GLOBAL_NODES_PER_TICK)break;
                        operations++;
                        try{valid=backend.validate(r,List.of());}catch(RuntimeException changed){valid=false;}
                    }
                }
                if(!valid){journal.cancelUnplanned(done.id(),done.failure()==null?"SEAL_CAS_FAILED_UNUSED":"GRAPH_FAILURE_UNUSED");cancelled++;}
                else {journal.seal(done.id(),done.session());handoffs.addLast(new Delivery(done.id(),0));sealed++;}
                validationCursor.remove(done.id());scheduler.acknowledge(done.id());
            }
            // One output per reservation per turn; blocked sinks cannot starve other owners.
            int attempts=0,turns=handoffs.size();
            while(!handoffs.isEmpty()&&attempts<MAX_HANDOFFS_PER_TICK&&turns-->0&&operations<StressScheduler.GLOBAL_NODES_PER_TICK){
                operations++;
                Delivery delivery=handoffs.removeFirst();var e=journal.get(delivery.id);
                if(delivery.index>=e.plans().size()){
                    journal.acknowledgeHandoff(delivery.id);closed++;attempts++;continue;
                }
                var plan=e.plans().get(delivery.index);attempts++;
                boolean accepted=backend.enqueue(applyId(delivery.id,plan.node().cell()),e.reservation(),plan);
                if(accepted){delivered++;handoffs.addLast(new Delivery(delivery.id,delivery.index+1));}
                else handoffs.addLast(delivery);
            }
            return new TickResult(admitted,operations,sealed,cancelled,delivered,closed);
        }catch(IOException|RuntimeException failure){
            // In-memory cursor may now be ahead of a failed fsync. Reopen from durable files.
            faulted=true;throw failure;
        }
    }
    public int queued(){return waiting.size()+scheduler.queued()+scheduler.completed().size()+handoffs.size();}
    public boolean faulted(){return faulted||journal.faulted();}
    private void ensureHealthy()throws IOException{if(faulted())throw new IOException("stress coordinator halted; reopen journal");}
}
