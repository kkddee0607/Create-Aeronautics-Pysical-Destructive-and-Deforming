package com.physicaldestroydeform.stress;

import java.util.*;
import static com.physicaldestroydeform.stress.StressPropagation.*;

/** One instance per server, shared by ALL World/Plot stress jobs. Server-thread only.
 * A repeated tick call cannot renew the global allowance. A waiting owner receives
 * one turn per tick, then rotates behind other owners. No mutation or Q issuance.
 */
public final class StressScheduler {
    public static final int GLOBAL_NODES_PER_TICK=512,MAX_JOBS=128,QUANTUM=16;
    public enum Admission { ACCEPTED, DUPLICATE, CAPACITY }
    public record Completion(String id,Session session,RuntimeException failure) {}
    private record Work(String id,Session session,Graph graph) {}
    private final Deque<Work> queue=new ArrayDeque<>();
    private final Map<String,Work> live=new HashMap<>();
    private final Map<String,Completion> completed=new LinkedHashMap<>();
    private long tick=Long.MIN_VALUE;
    private int used;
    private final Set<String> ranThisTick=new HashSet<>();
    public Admission submit(String id,Session session,Graph graph){
        if(id==null||id.isBlank())throw new IllegalArgumentException("missing stress job id");
        Objects.requireNonNull(session);Objects.requireNonNull(graph);
        if(live.containsKey(id)||completed.containsKey(id))return Admission.DUPLICATE;
        if(live.size()+completed.size()>=MAX_JOBS)return Admission.CAPACITY;
        Work work=new Work(id,session,graph);live.put(id,work);queue.addLast(work);return Admission.ACCEPTED;
    }
    public int tick(long gameTick){
        if(gameTick<0||gameTick<tick)throw new IllegalArgumentException("stress scheduler clock rollback");
        if(gameTick!=tick){tick=gameTick;used=0;ranThisTick.clear();}
        int startUsed=used,remaining=queue.size();
        while(!queue.isEmpty()&&remaining-->0&&used<GLOBAL_NODES_PER_TICK){
            Work work=queue.removeFirst();
            if(!ranThisTick.add(work.id)){queue.addLast(work);continue;}
            try {
                int n=work.session.advance(work.graph,gameTick,Math.min(QUANTUM,GLOBAL_NODES_PER_TICK-used));
                used+=n;
                if(work.session.ledger().finished()){
                    live.remove(work.id);completed.put(work.id,new Completion(work.id,work.session,null));
                }else queue.addLast(work);
            }catch(RuntimeException failure){
                // Bound even a failing reader: its attempted quantum cannot be reused this tick.
                used=Math.min(GLOBAL_NODES_PER_TICK,used+QUANTUM);
                live.remove(work.id);completed.put(work.id,new Completion(work.id,work.session,failure));
            }
        }
        return used-startUsed;
    }
    /** Remain retained until the caller has durably sealed/cancelled the reservation. */
    public List<Completion> completed(){return List.copyOf(completed.values());}
    public void acknowledge(String id){
        if(completed.remove(id)==null)throw new IllegalArgumentException("job not completed");
    }
    public int queued(){return queue.size();}
    public int usedThisTick(){return used;}
}
