package com.physicaldestroydeform.stress;

import com.physicaldestroydeform.*;
import com.physicaldestroydeform.state.SparseStateKey;
import com.physicaldestroydeform.world.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.physicaldestroydeform.stress.StressPropagation.*;

class StressCommitHandoffTest {
    @TempDir Path directory;
    private static final DamageModel.Settings D=DamageModel.Settings.defaults();
    private static final MaterialProperties M=new MaterialProperties(0,1024,0,0,false);
    private static SparseStateKey key(String dimension,int x){return new SparseStateKey(dimension,"WORLD",dimension,1,x,64,0,"minecraft:stone");}
    private static Node node(SparseStateKey k){return new Node(StressCommitHandoff.cellFor(k),k.fingerprint(),"rev0",M);}
    private static StressReservationJournal.Reservation reservation(){
        return new StressReservationJournal.Reservation("credit",1,10000,Policy.defaults(),D,
                List.of(new Seed(node(key("minecraft:overworld",0)),100,null)));
    }
    private static StressReservationJournal.Entry sealed(){
        return new StressReservationJournal.Entry(reservation(),StressReservationJournal.Phase.SEALED,
                List.of(planned()),new Ledger(100,0,60,15,25,0,1,true,
                java.util.Map.of(Unused.LOOP_OR_LATE_UNUSED,25.0)),"PLANNED_NOT_APPLIED");
    }
    private static PlannedNode planned(){return new PlannedNode(node(key("minecraft:overworld",1)),1,100,
            DamageModel.plan(100,M,new BlockDamageState(),D));}
    private static WorldCommitCore.Intent intent(PlannedNode p){return new WorldCommitCore.Intent(
            StressReservationJournal.applyId("credit",p.node().cell()),key("minecraft:overworld",1),
            "rev0",p.incomingQ(),M,1,200,false);}
    private WorldPersistentJournal journal()throws Exception{return new WorldPersistentJournal(directory.resolve("store"),directory.resolve("legacy.wal"));}
    @Test void handoffPersistsExactSettingsQAndDeadlineAndDeduplicates()throws Exception{
        var j=journal();var core=new WorldCommitCore(j,D,64);var p=planned();var i=intent(p);
        assertTrue(StressCommitHandoff.offer(core,sealed(),p,i,11199));
        var saved=journal().receipt(i.applyId()).pending();
        assertEquals(11200,saved.expiresGameTick());assertEquals(D,saved.settings());
        assertEquals(i,saved.intent());assertEquals(200,saved.intent().eventTick());
        assertTrue(StressCommitHandoff.offer(core,sealed(),p,i,11201));
        assertEquals(1,core.queued());assertEquals(1,j.receiptCount());
    }
    @Test void lateHandoffIsDurablyAbortedWithoutQueueing()throws Exception{
        var j=journal();var core=new WorldCommitCore(j,D,64);var p=planned();var i=intent(p);
        assertTrue(StressCommitHandoff.offer(core,sealed(),p,i,11201));
        assertEquals(WorldReceiptJournal.Status.ABORTED,journal().receipt(i.applyId()).status());
        assertEquals(0,core.queued());assertEquals(0,j.size());
    }
    @Test void otherOwnerOrFullSourceAmountCannotBeSubstituted()throws Exception{
        var core=new WorldCommitCore(journal(),D,64);var p=planned();var i=intent(p);
        var other=new WorldCommitCore.Intent(i.applyId(),key("minecraft:the_nether",1),"rev0",100,M,1,200,false);
        var doubleSpend=new WorldCommitCore.Intent(i.applyId(),i.key(),"rev0",400,M,1,200,false);
        assertThrows(IllegalArgumentException.class,()->StressCommitHandoff.offer(core,sealed(),p,other,10001));
        assertThrows(IllegalArgumentException.class,()->StressCommitHandoff.offer(core,sealed(),p,doubleSpend,10001));
        assertEquals(0,core.queued());
    }
    @Test void stableOwnerEncodingSeparatesGenerationAndCoordinate(){
        var k=key("minecraft:overworld",1);
        assertEquals(StressCommitHandoff.cellFor(k),StressCommitHandoff.cellFor(key("minecraft:overworld",1)));
        assertNotEquals(StressCommitHandoff.cellFor(k),StressCommitHandoff.cellFor(key("minecraft:overworld",2)));
        assertNotEquals(StressCommitHandoff.cellFor(k),StressCommitHandoff.cellFor(new SparseStateKey(
                k.dimension(),k.ownerSpace(),k.ownerId(),2,k.x(),k.y(),k.z(),k.fingerprint())));
    }
    @Test void unsealedOrUnlistedOutputCannotEnterCommitQueue()throws Exception{
        var core=new WorldCommitCore(journal(),D,64);var p=planned();var i=intent(p);
        var unsealed=new StressReservationJournal.Entry(reservation(),StressReservationJournal.Phase.RESERVED,
                List.of(),null,"RESERVED");
        assertThrows(IllegalArgumentException.class,()->StressCommitHandoff.offer(core,unsealed,p,i,10001));
        var unlisted=new PlannedNode(p.node(),2,p.incomingQ(),p.plan());
        assertThrows(IllegalArgumentException.class,()->StressCommitHandoff.offer(core,sealed(),unlisted,i,10001));
        assertEquals(0,core.queued());
    }
}
