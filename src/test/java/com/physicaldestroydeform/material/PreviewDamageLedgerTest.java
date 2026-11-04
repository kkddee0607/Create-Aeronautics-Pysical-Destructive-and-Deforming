package com.physicaldestroydeform.material;

import com.physicaldestroydeform.BlockDamageState;
import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.MaterialProperties;
import com.physicaldestroydeform.collision.ContactCreditLedger;
import com.physicaldestroydeform.collision.GameImpactCredit;
import java.util.List;
import java.util.Optional;
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
}
