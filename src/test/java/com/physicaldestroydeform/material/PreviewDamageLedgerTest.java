package com.physicaldestroydeform.material;

import com.physicaldestroydeform.BlockDamageState;
import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.MaterialProperties;
import com.physicaldestroydeform.collision.ContactCreditLedger;
import com.physicaldestroydeform.collision.GameImpactCredit;
import com.physicaldestroydeform.state.SparseDamageState;
import com.physicaldestroydeform.state.SparseStateCodec;
import com.physicaldestroydeform.state.SparseStateKey;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreviewDamageLedgerTest {
    private static final String WORLD="WORLD:minecraft:overworld";
    private static final String FP="minecraft:stone|Block{minecraft:stone}";
    private static ContactCreditLedger.Grant grant(long episode,double q) {
        var pair=GameImpactCredit.Pair.canonical("run","minecraft:overworld",
                new GameImpactCredit.Owner("PLOT:00000000-0000-0000-0000-000000000001",1),
                new GameImpactCredit.Owner(WORLD,1));
        return new ContactCreditLedger.Grant(new ContactCreditLedger.CreditId(pair,episode),
                ContactCreditLedger.Source.SABLE_POST_EFFECT,ContactCreditLedger.Status.ISSUED,
                100+episode,q,List.of(),List.of(new ContactCreditLedger.Share(
                    new GameImpactCredit.Target(WORLD,"1,64,2",FP),q)),0,1,
                ContactCreditLedger.ALGORITHM_REVISION);
    }
    private static PreviewDamageLedger.Result apply(PreviewDamageLedger state,long episode,double q) {
        return state.record(grant(episode,q),1,100+episode,
                fp->new MaterialProperties(60,200,10,0,false),DamageModel.Settings.defaults(),
                key->Optional.empty());
    }
    @Test void sameCauseOnceButReimpactCarriesFractionalDamage() {
        var state=new PreviewDamageLedger();
        var first=apply(state,1,0.5);
        assertEquals(PreviewDamageLedger.ResultStatus.ACCEPTED,first.status());
        assertEquals(1,state.handledCredits());
        long firstTotal=state.fixedTotal();
        assertTrue(firstTotal>0);
        assertEquals(0,first.report().plans().getFirst().plan().plannedDamageU8());
        var duplicate=apply(state,1,0.5);
        assertEquals(PreviewDamageLedger.ResultStatus.DUPLICATE,duplicate.status());
        assertEquals(firstTotal,state.fixedTotal());
        var next=apply(state,2,0.5);
        assertEquals(PreviewDamageLedger.ResultStatus.ACCEPTED,next.status());
        assertEquals(firstTotal*2,state.fixedTotal());
        assertEquals(2,state.handledCredits());
        assertEquals(1,state.size());
        assertEquals(2,state.accepted());
        assertEquals(1,state.duplicates());
    }
    @Test void twoIndependentOwnersAndCodecRoundtripPreserveFingerprint() {
        var shadow=new PreviewDamageLedger();
        apply(shadow,1,1.25);apply(shadow,2,0.5);
        byte[] bytes=shadow.verifyRoundTrip();
        var reload=new PreviewDamageLedger();reload.restore(bytes);
        assertEquals(shadow.fixedTotal(),reload.fixedTotal());
        assertEquals(shadow.snapshot(),reload.snapshot());
        assertEquals(0,reload.handledCredits());
        assertEquals(1,shadow.roundTrips());
        byte[] tampered=bytes.clone();tampered[14]^=1;
        assertThrows(IllegalArgumentException.class,()->reload.restore(tampered));
        assertEquals(shadow.fixedTotal(),reload.fixedTotal());
        assertEquals(shadow.snapshot(),reload.snapshot());
    }
    @Test void rejectedGrantCannotMutateShadow() {
        var shadow=new PreviewDamageLedger();
        var original=grant(1,1);
        var rejected=new ContactCreditLedger.Grant(original.id(),original.source(),
                ContactCreditLedger.Status.DUPLICATE_CAUSE,original.tick(),0,
                List.of(),List.of(),0,0,original.algorithmRevision());
        assertThrows(IllegalArgumentException.class,()->shadow.record(rejected,1,101,
                x->new MaterialProperties(50,10,0),DamageModel.Settings.defaults(),
                x->Optional.empty()));
        assertEquals(0,shadow.size());assertEquals(0,shadow.handledCredits());
    }
    @Test void roundTripRunsOnceForEachChangedSnapshot() {
        var shadow=new PreviewDamageLedger();
        assertTrue(shadow.verifyPendingRoundTrip().isEmpty());
        assertFalse(shadow.changedSinceRestore());

        apply(shadow,1,1.25);
        assertTrue(shadow.changedSinceRestore());
        assertTrue(shadow.verifyPendingRoundTrip().isPresent());
        assertTrue(shadow.verifyPendingRoundTrip().isEmpty());
        apply(shadow,1,1.25);
        assertTrue(shadow.verifyPendingRoundTrip().isEmpty());
        assertEquals(1,shadow.roundTrips());

        apply(shadow,2,0.5);
        assertTrue(shadow.verifyPendingRoundTrip().isPresent());
        assertEquals(2,shadow.roundTrips());
    }
    @Test void restoreRebuildsFixedTotalAndClearResetsSnapshotTracking() {
        var shadow=new PreviewDamageLedger();
        apply(shadow,1,1.25);
        byte[] encoded=shadow.verifyRoundTrip();
        long savedTotal=shadow.fixedTotal();
        apply(shadow,2,0.5);
        assertTrue(shadow.fixedTotal()>savedTotal);

        shadow.restore(encoded);
        assertEquals(savedTotal,shadow.fixedTotal());
        assertFalse(shadow.changedSinceRestore());
        assertTrue(shadow.verifyPendingRoundTrip().isPresent());
        assertTrue(shadow.verifyPendingRoundTrip().isEmpty());
        var update=apply(shadow,3,0.5);
        assertEquals(savedTotal,update.fixedBefore());
        assertEquals(savedTotal+update.report().fixedIncrementTotal(),update.fixedAfter());

        shadow.clear();
        assertEquals(0,shadow.fixedTotal());
        assertEquals(0,shadow.size());
        assertEquals(0,shadow.roundTrips());
        assertFalse(shadow.changedSinceRestore());
        assertTrue(shadow.verifyPendingRoundTrip().isEmpty());
    }
    @Test void capacityRejectionKeepsExistingDamageAndAcceptsUpdatesWithoutNewKeys() {
        var shadow=new PreviewDamageLedger();
        var states=IntStream.range(0,PreviewDamageLedger.MAX_ENTRIES).mapToObj(x->
                new SparseDamageState(new SparseStateKey("minecraft:overworld","WORLD",
                        "minecraft:overworld",1,x,64,2,FP),600,0,0,1,1,1)).toList();
        shadow.restore(SparseStateCodec.encode(states));
        assertTrue(shadow.verifyPendingRoundTrip().isPresent());
        long before=(long)states.size()*(600*BlockDamageState.FIXED_SCALE+1);
        assertEquals(2_516_598_784L,before);
        assertTrue(before>Integer.MAX_VALUE);
        assertEquals(before,shadow.fixedTotal());
        var old=grant(1,1.25);
        var oversized=new ContactCreditLedger.Grant(old.id(),old.source(),old.status(),old.tick(),2.5,
                List.of(),List.of(old.b().getFirst(),new ContactCreditLedger.Share(
                        new GameImpactCredit.Target(WORLD,"-1,64,2",FP),1.25)),0,2,old.algorithmRevision());
        var result=shadow.record(oversized,1,101,
                fp->new MaterialProperties(60,200,10,0,false),DamageModel.Settings.defaults(),
                key->Optional.empty());

        assertEquals(PreviewDamageLedger.ResultStatus.CAPACITY,result.status());
        assertEquals(before,shadow.fixedTotal());
        assertEquals(states.get(1),shadow.state(states.get(1).key()));
        assertEquals(0,shadow.handledCredits());
        assertEquals(1,shadow.capacityRejected());
        assertFalse(shadow.changedSinceRestore());
        assertTrue(shadow.verifyPendingRoundTrip().isEmpty());

        var existingUpdate=apply(shadow,1,1.25);
        assertEquals(PreviewDamageLedger.ResultStatus.ACCEPTED,existingUpdate.status());
        assertEquals(PreviewDamageLedger.MAX_ENTRIES,shadow.size());
        assertEquals(before,existingUpdate.fixedBefore());
        assertEquals(347L,existingUpdate.report().fixedIncrementTotal());
        assertEquals(before+existingUpdate.report().fixedIncrementTotal(),shadow.fixedTotal());
        assertEquals(2_516_599_131L,shadow.fixedTotal());
    }
}
