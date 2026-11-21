package com.physicaldestroydeform.world;

import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.MaterialProperties;
import com.physicaldestroydeform.state.SparseStateKey;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StressSealedQueuePressureTest {
    private static final DamageModel.Settings SETTINGS=DamageModel.Settings.defaults();
    private static WorldCommitCore.Intent intent(int i){
        return new WorldCommitCore.Intent("large-"+i,new SparseStateKey("minecraft:overworld","WORLD","minecraft:overworld",1,i,64,0,"minecraft:stone"),"r",100,
                new MaterialProperties(0,1024,0,0,false),1,100,true);
    }
    // Keep the real queue and deadline logic. Only the durable I/O boundary is
    // substituted so the cap test does not fsync 16384 fixture records.
    private static final class MemoryJournal implements WorldJournal {
        final Map<String,WorldReceiptJournal.Receipt> receipts=new LinkedHashMap<>();
        int writes;
        MemoryJournal(){
            for(int i=0;i<16384;i++){
                var it=intent(i);
                receipts.put(it.applyId(),new WorldReceiptJournal.Receipt(it.applyId(),it.key(),WorldReceiptJournal.Status.DEFERRED,
                        "QUEUED",100,new WorldReceiptJournal.Pending(it,SETTINGS,i,1300)));
            }
        }
        public boolean supportsPendingPayload(){return true;}
        public WorldReceiptJournal.State get(SparseStateKey key){return null;}
        public WorldReceiptJournal.Receipt receipt(String id){return receipts.get(id);}
        public int size(){return 0;}
        public int receiptCount(){return receipts.size();}
        public long bytes(){return 0;}
        public List<WorldReceiptJournal.Receipt> uncertain(){return List.copyOf(receipts.values());}
        public List<WorldReceiptJournal.State> stateSnapshot(){return List.of();}
        public boolean hasPosition(String d,int x,int y,int z){return false;}
        public void transition(WorldReceiptJournal.Receipt r,WorldReceiptJournal.State s){writes++;receipts.put(r.id(),r);}
        public void discardAtPosition(String d,int x,int y,int z){}
        public void discardStale(SparseStateKey key){}
    }
    @Test void durableSourceRetainsOwnershipWhileQueueIsFullAndExpiresOnlyAtOriginalDeadline(){
        var journal=new MemoryJournal();
        var core=new WorldCommitCore(journal,SETTINGS,64);
        var tail=intent(16384);
        var wait=core.trySubmitSealed(tail,SETTINGS,101,1300);
        assertEquals(WorldCommitCore.Outcome.DEFERRED,wait.result());
        assertNull(wait.receipt());assertNull(journal.receipt(tail.applyId()));
        assertEquals(0,journal.writes);assertEquals(16384,core.queued());
        // An existing receipt is still recognized while full.
        assertEquals(WorldCommitCore.Outcome.DUPLICATE,core.trySubmitSealed(intent(0),SETTINGS,101,1300).result());
        var expired=core.trySubmitSealed(tail,SETTINGS,1301,1300);
        assertEquals(WorldCommitCore.Outcome.ABORTED,expired.result());
        assertEquals("DEFER_TTL_EXPIRED",expired.reason());
        assertEquals(1,journal.writes);assertEquals(16384,core.queued());
        assertEquals(WorldCommitCore.Outcome.DUPLICATE,core.trySubmitSealed(tail,SETTINGS,1302,1300).result());
    }
}
