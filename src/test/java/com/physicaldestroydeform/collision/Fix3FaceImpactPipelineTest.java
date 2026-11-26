package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.MaterialProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Regression of the real face-recovery -> ledger -> inward F/Q entry path. */
class Fix3FaceImpactPipelineTest {
    @Test void twentyFiveFaceCellsReceiveOneSaturatedEventAndTaperInward() {
        var roots=SuperFixBilateralSurfaceTest.roots();
        var face=SuperFixBilateralSurfaceTest.surface(false,false,false,0);
        assertFalse(face.incomplete());assertEquals(25,face.matches().size());
        var regions=SableContactRegions.mergeBilateralPairs(roots,face.matches());
        var pair=SuperFixBilateralSurfaceTest.PAIR;
        var credit=new GameImpactCredit(GameImpactCredit.Settings.provisional());
        credit.step(new GameImpactCredit.Frame(pair,1,List.of(),true,false,null,null));
        credit.step(new GameImpactCredit.Frame(pair,2,List.of(),true,false,null,null));
        var huge=new GameImpactCredit.ObservedForce(-1,7,1e12,0);
        var approved=credit.step(new GameImpactCredit.Frame(pair,3,List.of(huge),false,true,
                roots.getFirst().a(),roots.getFirst().b()));
        assertEquals(GameImpactCredit.Reason.APPROVED,approved.reason());
        assertEquals(40_000_000d,approved.peakForce(),0);

        var ledger=new ContactCreditLedger();
        var grant=ledger.issueContactSeeds(pair,approved,
                ContactCreditLedger.Source.SABLE_POST_EFFECT,regions,0,0,
                (from,x,y,z)->from.ownerKey().equals(SuperFixBilateralSurfaceTest.WORLD)&&x>=0&&x<5&&z>=0&&z<5&&y>=65&&y<=68
                        ?ForcePropagation.Probe.present(new GameImpactCredit.Target(
                                SuperFixBilateralSurfaceTest.WORLD,x+","+y+","+z,
                                "minecraft:stone"))
                        :ForcePropagation.Probe.empty(),
                ignored->new MaterialProperties(0,255,0,0,false),
                GameImpactCredit.Settings.provisional(),DamageModel.Settings.defaults(),
                Map.of(SuperFixBilateralSurfaceTest.WORLD,new ForcePropagation.Orientation(
                        new FrameMath.Vec(0,1,0),"NATIVE_OWNER_LOCAL")),
                null,null);
        assertEquals(ContactCreditLedger.Status.ISSUED,grant.status());
        assertEquals(25,grant.propagationB().shares().stream()
                .filter(s->s.layer()==0).count());
        assertEquals(40_000_000d,grant.inputF(),1e-6);
        assertEquals(grant.issuedQ(),grant.accounted(),1e-4);
        assertTrue(grant.propagationB().audit().maxDepth()>0);
        assertEquals(ContactCreditLedger.Status.DUPLICATE_CAUSE,
                ledger.issueContactSeeds(pair,approved,ContactCreditLedger.Source.SABLE_POST_EFFECT,
                        regions,0,0,(from,x,y,z)->ForcePropagation.Probe.empty(),
                        ignored->new MaterialProperties(0,255,0,0,false),
                        GameImpactCredit.Settings.provisional(),DamageModel.Settings.defaults(),
                        Map.of(),null,null).status());
    }
}
