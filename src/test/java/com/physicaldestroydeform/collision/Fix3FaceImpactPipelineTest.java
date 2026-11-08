package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.MaterialProperties;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Regression of the real face-recovery -> ledger -> inward F/Q entry path. */
class Fix3FaceImpactPipelineTest {
    @Test void twentyFiveFaceCellsReceiveOneSaturatedEventAndTaperInward() {
        var roots=List.of(ContactFacePatchTest.target(0,0),ContactFacePatchTest.target(0,4),
                ContactFacePatchTest.target(4,0),ContactFacePatchTest.target(4,4));
        var face=ContactFacePatch.reconstruct(ContactFacePatchTest.DIM,
                ContactFacePatchTest.OWNER,BlockPos.ZERO,roots,ContactFacePatchTest.corners(),
                (pos,p)->ContactFacePatchTest.target(pos.getX(),pos.getZ()));
        assertEquals("SHAPE_VERIFIED_FACE",face.reason());
        var all=new HashSet<>(roots);
        all.addAll(face.added());
        assertEquals(25,all.size());
        var pair=GameImpactCredit.Pair.canonical("face-impact","minecraft:overworld",
                new GameImpactCredit.Owner("PLOT:verified",1),
                new GameImpactCredit.Owner(ContactFacePatchTest.OWNER,1));
        var credit=new GameImpactCredit(GameImpactCredit.Settings.provisional());
        credit.step(new GameImpactCredit.Frame(pair,1,List.of(),true,false,null,null));
        credit.step(new GameImpactCredit.Frame(pair,2,List.of(),true,false,null,null));
        var huge=new GameImpactCredit.ObservedForce(-1,7,1e12,0);
        var approved=credit.step(new GameImpactCredit.Frame(pair,3,List.of(huge),false,true,
                null,roots.getFirst()));
        assertEquals(GameImpactCredit.Reason.APPROVED,approved.reason());
        assertEquals(40_000_000d,approved.peakForce(),0);

        var regions=new ArrayList<ContactCreditLedger.Region>();
        for(var t:all)regions.add(new ContactCreditLedger.Region(null,t));
        var ledger=new ContactCreditLedger();
        var grant=ledger.issuePropagated(pair,approved,
                ContactCreditLedger.Source.SABLE_POST_EFFECT,regions,
                (from,x,y,z)->x>=0&&x<5&&z>=0&&z<5&&y>=61&&y<=64
                        ?ForcePropagation.Probe.present(new GameImpactCredit.Target(
                                ContactFacePatchTest.OWNER,x+","+y+","+z,
                                y==64?"minecraft:grass_block":"minecraft:stone"))
                        :ForcePropagation.Probe.empty(),
                ignored->new MaterialProperties(0,255,0,0,false),
                GameImpactCredit.Settings.provisional(),DamageModel.Settings.defaults(),
                Map.of(ContactFacePatchTest.OWNER,new ForcePropagation.Orientation(
                        new FrameMath.Vec(0,-1,0),"NATIVE_OWNER_LOCAL")));
        assertEquals(ContactCreditLedger.Status.ISSUED,grant.status());
        assertEquals(25,grant.propagationB().shares().stream()
                .filter(s->s.layer()==0).count());
        assertEquals(40_000_000d,grant.inputF(),1e-6);
        assertEquals(grant.issuedQ(),grant.accounted(),1e-4);
        assertTrue(grant.propagationB().audit().maxDepth()>0);
        assertEquals(ContactCreditLedger.Status.DUPLICATE_CAUSE,
                ledger.issuePropagated(pair,approved,ContactCreditLedger.Source.SABLE_POST_EFFECT,
                        regions,(from,x,y,z)->ForcePropagation.Probe.empty(),
                        ignored->new MaterialProperties(0,255,0,0,false),
                        GameImpactCredit.Settings.provisional(),DamageModel.Settings.defaults()).status());
    }
}
