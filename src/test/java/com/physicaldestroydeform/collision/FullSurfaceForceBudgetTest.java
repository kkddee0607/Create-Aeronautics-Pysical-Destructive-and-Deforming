package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.MaterialProperties;
import com.physicaldestroydeform.DamageModel;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Recovered corners become a 5x5 face, but cannot multiply the source F/Q. */
final class FullSurfaceForceBudgetTest {
    @Test void oneNativeEventSplitsFEquallyAcrossAllVerifiedFaceCells() {
        String dim="minecraft:overworld";
        var pair=GameImpactCredit.Pair.canonical("one-session",dim,
                new GameImpactCredit.Owner("PLOT:one",1),new GameImpactCredit.Owner("WORLD:"+dim,1));
        var force=new GameImpactCredit.Settings(2,2,.161,1000000,2,4096,600);
        var policy=new GameImpactCredit(force);
        var plot=new GameImpactCredit.Target(pair.a().stableKey(),"0,0,0","minecraft:stone");
        var world=new GameImpactCredit.Target(pair.b().stableKey(),"0,64,0","minecraft:stone");
        var nativePoints=List.of(new GameImpactCredit.ObservedForce(7,-1,20000,0),
                new GameImpactCredit.ObservedForce(7,-1,20000,1),
                new GameImpactCredit.ObservedForce(7,-1,20000,2),
                new GameImpactCredit.ObservedForce(7,-1,20000,3));
        var decision=policy.step(new GameImpactCredit.Frame(pair,5,nativePoints,false,true,plot,world));
        assertTrue(decision.credited());
        var regions=new ArrayList<ContactCreditLedger.Region>();
        for(int x=0;x<5;x++)for(int z=0;z<5;z++){
            var contactA=new GameImpactCredit.Target(pair.a().stableKey(),x+",0,"+z,"minecraft:stone");
            var contactB=new GameImpactCredit.Target(pair.b().stableKey(),x+",64,"+z,"minecraft:stone");
            regions.add(new ContactCreditLedger.Region(contactA,contactB));
        }
        var ledger=new ContactCreditLedger();
        var grant=ledger.issuePropagated(pair,decision,ContactCreditLedger.Source.SABLE_POST_EFFECT,
                regions,(from,x,y,z)->ForcePropagation.Probe.empty(),
                fp->new MaterialProperties(0,32,0,0,false),force,DamageModel.Settings.defaults());
        assertTrue(grant.credited(),ledger.lastPropagationFailure());
        assertEquals(25,grant.propagationA().shares().size());
        assertEquals(25,grant.propagationB().shares().size());
        assertEquals(20000,grant.inputF(),1e-7);
        assertEquals(20000/2d,grant.propagationA().inputF(),1e-7);
        assertEquals(20000/2d,grant.propagationB().inputF(),1e-7);
        assertEquals(20000/50d,grant.propagationA().shares().getFirst().allocatedF(),1e-7);
        assertEquals(20000/50d,grant.propagationB().shares().getFirst().allocatedF(),1e-7);
        assertEquals(grant.issuedQ(),grant.accounted(),1e-6);
        assertEquals(ContactCreditLedger.Status.DUPLICATE_CAUSE,
                ledger.issue(pair,decision,ContactCreditLedger.Source.SABLE_POST_EFFECT,regions).status());
    }
}
