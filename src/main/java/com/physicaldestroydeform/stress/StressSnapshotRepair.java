package com.physicaldestroydeform.stress;

import java.util.*;
import static com.physicaldestroydeform.stress.StressPropagation.*;
import static com.physicaldestroydeform.stress.StressReservationJournal.*;

/** Frozen-graph deletion only. An old solid cell becomes a denied sink (same
 * edge weight, no newly installed block receives Q). Old empty/denied cells
 * retain their original topology. Unknown history cannot be reconstructed. */
final class StressSnapshotRepair {
    private StressSnapshotRepair() {}
    enum Verdict { WAIT, SAFE, INCOMPATIBLE }
    static Map<Cell,Probe> retire(Entry entry,Map<Cell,Probe> frozen,Set<Cell> changed){
        if(changed.isEmpty())return null;
        var roots=new HashSet<Cell>();for(var s:entry.reservation().seeds())roots.add(s.node().cell());
        var published=new HashSet<Cell>();for(var plan:entry.plans())published.add(plan.node().cell());
        var next=new TreeMap<>(frozen);
        for(var cell:changed){
            if(published.contains(cell))return null;
            var old=frozen.get(cell);
            if(old==null){if(!roots.contains(cell))return null;next.put(cell,Probe.denied());continue;}
            if(old.state()==State.UNAVAILABLE)return null;
            if(old.state()==State.SOLID)next.put(cell,Probe.denied());
            // EMPTY is an original absent edge, DENIED an original sink.
        }
        return next;
    }
    /** A new replay may extend, never revise, a published prefix or spend
     * already settled unused Q. Compare before any new output is persisted. */
    static Verdict verifyPrefix(Entry old,Session replay){
        var plans=replay.partialPlans();var prefix=old.plans();
        int n=Math.min(plans.size(),prefix.size());
        if(!plans.subList(0,n).equals(prefix.subList(0,n)))return Verdict.INCOMPATIBLE;
        if(plans.size()<prefix.size())return replay.finished()?Verdict.INCOMPATIBLE:Verdict.WAIT;
        if(old.ledger()!=null){
            var before=old.ledger();var after=replay.ledger();
            if(!atLeast(after.directQ(),before.directQ())||!atLeast(after.absorbedQ(),before.absorbedQ())||!atLeast(after.plasticUnusedQ(),before.plasticUnusedQ())
                    ||!atLeast(after.unusedQ(),before.unusedQ()))return replay.finished()?Verdict.INCOMPATIBLE:Verdict.WAIT;
            for(var item:before.reasons().entrySet())if(!atLeast(after.reasons().getOrDefault(item.getKey(),0.0),item.getValue()))return replay.finished()?Verdict.INCOMPATIBLE:Verdict.WAIT;
        }
        return Verdict.SAFE;
    }
    private static boolean atLeast(double after,double before){
        return Double.isFinite(after)&&Double.isFinite(before)&&after>=before; // never spend a rounded-away old partition
    }
    static boolean frozenSink(Probe probe){return probe!=null&&(probe.state()==State.EMPTY||probe.state()==State.DENIED);}
}
