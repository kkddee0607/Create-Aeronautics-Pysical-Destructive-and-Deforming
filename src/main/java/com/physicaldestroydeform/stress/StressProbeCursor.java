package com.physicaldestroydeform.stress;

import java.util.ArrayList;
import java.util.List;
import static com.physicaldestroydeform.stress.StressPropagation.*;

/** Resumes one graph operation after a shared budget yield, including budgets below seven probes.
 * Only already charged, frozen reads are replayed. Session.advance(1) does not mutate a node
 * until its identity and all six edges have been read, so a yielded operation is retryable.
 */
final class StressProbeCursor {
    private record Read(Cell cell,Probe probe) {}
    private final List<Read> reads=new ArrayList<>(7);
    private boolean charged;
    int advance(Session session,Graph frozen,long tick,Runnable chargeOperation) {
        if(!charged){chargeOperation.run();charged=true;}
        int[] index={0};
        int result=session.advance(cell->{
            int position=index[0]++;
            if(position<reads.size()){
                Read prior=reads.get(position);
                if(!prior.cell().equals(cell))throw new IllegalStateException("changed yielded stress operation");
                return prior.probe();
            }
            Probe probe=frozen.read(cell);reads.add(new Read(cell,probe));return probe;
        },tick,1);
        reads.clear();charged=false;return result;
    }
}
