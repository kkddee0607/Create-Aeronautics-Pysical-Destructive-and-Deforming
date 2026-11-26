package com.physicaldestroydeform.stress;

import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.TreeMap;

/** Server-thread wake index only; original Q and deadlines stay in the journal.
 * No tick scans of parked jobs. Each ID is indexed exactly once. */
final class StressResidualWait {
    private final TreeMap<Long,LinkedHashSet<String>> deadlines=new TreeMap<>();
    private final HashMap<String,Long> byId=new HashMap<>();
    void park(String id,long deadline){
        if(byId.putIfAbsent(id,deadline)!=null)throw new IllegalStateException("stress job already parked");
        deadlines.computeIfAbsent(deadline,k->new LinkedHashSet<>()).add(id);
    }
    boolean wakeOne(String id,Deque<String> ready){
        Long deadline=byId.remove(id);if(deadline==null)return false;
        var bucket=deadlines.get(deadline);bucket.remove(id);
        if(bucket.isEmpty())deadlines.remove(deadline);
        ready.addLast(id);return true;
    }
    void wake(long tick,boolean generationChanged,Deque<String> ready){
        while(!deadlines.isEmpty()&&(generationChanged||deadlines.firstKey()<=tick)){
            var bucket=deadlines.pollFirstEntry();
            for(var id:bucket.getValue()){byId.remove(id);ready.addLast(id);}
        }
    }
    int size(){return byId.size();}
    void clear(){deadlines.clear();byId.clear();}
    static boolean readyForResidualWait(StressReservationJournal.Entry entry,boolean originsRecovered,int deliveryIndex){
        return entry.phase()==StressReservationJournal.Phase.SEALED&&!entry.clockRolledBack()
                &&!entry.holdsOrigins()&&entry.ledger().finished()&&entry.ledger().pendingQ()>0
                &&originsRecovered&&deliveryIndex>=entry.plans().size();
    }
}
