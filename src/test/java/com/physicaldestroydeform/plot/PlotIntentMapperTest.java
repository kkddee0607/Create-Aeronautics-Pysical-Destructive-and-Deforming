package com.physicaldestroydeform.plot;

import com.physicaldestroydeform.MaterialProperties;
import com.physicaldestroydeform.collision.ContactCreditLedger;
import com.physicaldestroydeform.collision.GameImpactCredit;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlotIntentMapperTest {
    private static ContactCreditLedger.Grant mixed() {
        var world=new GameImpactCredit.Owner("WORLD:minecraft:overworld",1);
        var ship=new GameImpactCredit.Owner("PLOT:123e4567-e89b-12d3-a456-426614174000",1);
        var pair=GameImpactCredit.Pair.canonical("run-plot","minecraft:overworld",world,ship);
        var a=new ContactCreditLedger.Share(new GameImpactCredit.Target(world.stableKey(),
                "12,64,12","minecraft:stone|Block{minecraft:stone}"),5);
        var b=new ContactCreditLedger.Share(new GameImpactCredit.Target(ship.stableKey(),
                "12,64,12","minecraft:stone|Block{minecraft:stone}"),7);
        return new ContactCreditLedger.Grant(new ContactCreditLedger.CreditId(pair,1),
                ContactCreditLedger.Source.SABLE_POST_EFFECT,ContactCreditLedger.Status.ISSUED,
                100,14,List.of(a),List.of(b),2,1,"test");
    }
    @Test void sameCoordinatesDifferentOwnerNoDoubleDebit(){
        var grant=mixed();
        var stone=new MaterialProperties(50,150,10,10,false);
        var mapped=PlotIntentMapper.project(grant,7,f->stone,k->"owner-incarnation");
        assertEquals(1,mapped.plot().size());
        assertEquals(7.0,mapped.plot().getFirst().sealedQ(),1e-10);
        assertEquals(5.0,mapped.worldQ(),1e-10);
        assertEquals(2.0,grant.unused(),1e-10);
        assertEquals("WORLD",mapped.plot().getFirst().key().ownerSpace());
        assertEquals("PLOT",PlotKeyCodec.real(mapped.plot().getFirst().key()).ownerSpace());
        var blocked=PlotIntentMapper.project(grant,7,f->stone,k->null);
        assertTrue(blocked.plot().isEmpty());
        assertEquals(7.0,blocked.missingOwnerQ(),1e-10);
        assertEquals(5.0,blocked.worldQ(),1e-10);
    }
}
