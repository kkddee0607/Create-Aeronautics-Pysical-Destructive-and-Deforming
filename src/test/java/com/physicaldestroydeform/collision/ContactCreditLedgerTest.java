package com.physicaldestroydeform.collision;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.physicaldestroydeform.collision.GameImpactCredit.*;

/** §22.4, §22.5 and G-LEDGER-01: no per-point credit multiplication. */
final class ContactCreditLedgerTest {
    private static final Pair PAIR=Pair.canonical("server-epoch-1","minecraft:overworld",
            new Owner("PLOT:alpha",1),new Owner("PLOT:beta",1));
    private static Target target(String owner,int cell) {
        return new Target(owner,cell+",0,0","minecraft:stone");
    }
    private static Target a(int i){return target(PAIR.a().stableKey(),i);}
    private static Target b(int i){return target(PAIR.b().stableKey(),i);}
    private static final List<ObservedForce> SAMPLES=List.of(new ObservedForce(1,2,42,0));
    private static Decision approved(long tick,Pair pair,Target at,Target bt) {
        var e=new GameImpactCredit(new GameImpactCredit.Settings(2,2,0.25,64,2,4096,600));
        e.step(new Frame(pair,tick-2,List.of(),true,false,null,null));
        e.step(new Frame(pair,tick-1,List.of(),true,false,null,null));
        var result=e.step(new Frame(pair,tick,SAMPLES,false,true,at,bt));
        assertTrue(result.credited());
        return result;
    }
    @Test void fourReportedPointsOneBudget() {
        var policy=new GameImpactCredit(new GameImpactCredit.Settings(2,2,0.25,64,2,4096,600));
        policy.step(new Frame(PAIR,1,List.of(),true,false,null,null));
        policy.step(new Frame(PAIR,2,List.of(),true,false,null,null));
        var forces=List.of(new ObservedForce(1,2,42,0),new ObservedForce(1,2,42,1),
                new ObservedForce(1,2,42,2),new ObservedForce(1,2,42,3));
        var approved=policy.step(new Frame(PAIR,3,forces,false,true,a(1),b(1)));
        var ledger=new ContactCreditLedger();
        var four=List.of(new ContactCreditLedger.Region(a(1),b(1)),
                new ContactCreditLedger.Region(a(1),b(1)),
                new ContactCreditLedger.Region(a(1),b(1)),
                new ContactCreditLedger.Region(a(1),b(1)));
        var first=ledger.issue(PAIR,approved,ContactCreditLedger.Source.SABLE_POST_EFFECT,four);
        assertTrue(first.credited());
        assertEquals(10.0,first.issuedQ());
        assertEquals(5.0,first.allocatedA());
        assertEquals(5.0,first.allocatedB());
        assertEquals(10.0,first.accounted());
        assertEquals(1,first.a().size());
        assertEquals(ContactCreditLedger.Status.DUPLICATE_CAUSE,
                ledger.issue(PAIR,approved,ContactCreditLedger.Source.SPECIAL_CALLBACK_HINT,four).status());
        assertEquals(1,ledger.size());
    }
    @Test void fourIndependentTargetsShareOneGroupQ() {
        var ledger=new ContactCreditLedger();
        var regions=new ArrayList<ContactCreditLedger.Region>();
        for(int i=0;i<4;i++)regions.add(new ContactCreditLedger.Region(a(i),b(i)));
        var first=ledger.issue(PAIR,approved(3,PAIR,a(0),b(0)),
                ContactCreditLedger.Source.SABLE_POST_EFFECT,regions);
        assertEquals(ContactCreditLedger.Status.ISSUED,first.status());
        assertEquals(4,first.a().size());assertEquals(4,first.b().size());
        for(var allocation:first.a())assertEquals(1.25,allocation.q());
        for(var allocation:first.b())assertEquals(1.25,allocation.q());
        assertEquals(10.0,first.accounted());
    }
    @Test void oneVerifiedSideReservesOppositeHalf() {
        var grant=new ContactCreditLedger().issue(PAIR,approved(3,PAIR,a(1),null),
                ContactCreditLedger.Source.SABLE_POST_EFFECT,
                List.of(new ContactCreditLedger.Region(a(1),null)));
        assertEquals(5.0,grant.allocatedA());
        assertEquals(0.0,grant.allocatedB());
        assertEquals(5.0,grant.unused());
    }
    @Test void fiveToThirtyTwoTargetsShareFixedSideBudget() {
        for(int count:new int[]{5,8,16,32}) {
            var ledger=new ContactCreditLedger();
            var regions=new ArrayList<ContactCreditLedger.Region>();
            for(int i=0;i<count;i++)regions.add(new ContactCreditLedger.Region(a(i),b(i)));
            var grant=ledger.issue(PAIR,approved(3,PAIR,a(0),b(0)),
                    ContactCreditLedger.Source.SABLE_POST_EFFECT,regions);
            assertEquals(ContactCreditLedger.Status.ISSUED,grant.status());
            assertEquals(count,grant.a().size()); assertEquals(count,grant.b().size());
            assertEquals(10.0,grant.accounted(),1e-9);
            assertEquals(5.0,grant.allocatedA(),1e-9);
            assertEquals(5.0,grant.allocatedB(),1e-9);
        }
    }
    @Test void thirtyThreeTargetsFailClosedWithoutMintingBudget() {
        var ledger=new ContactCreditLedger();
        var regions=new ArrayList<ContactCreditLedger.Region>();
        for(int i=0;i<33;i++)regions.add(new ContactCreditLedger.Region(a(i),null));
        var grant=ledger.issue(PAIR,approved(3,PAIR,a(0),null),
                ContactCreditLedger.Source.SABLE_POST_EFFECT,regions);
        assertEquals(ContactCreditLedger.Status.INVALID_TARGET_SET,grant.status());
        assertEquals(0.0,grant.issuedQ());assertEquals(0,ledger.size());
    }
    @Test void wrongSideOwnerRejectedAndEpochIsolated() {
        var ledger=new ContactCreditLedger();
        var result=approved(3,PAIR,a(0),b(0));
        var wrong=ledger.issue(PAIR,result,ContactCreditLedger.Source.SABLE_POST_EFFECT,
                List.of(new ContactCreditLedger.Region(b(0),null)));
        assertEquals(ContactCreditLedger.Status.INVALID_TARGET_SET,wrong.status());
        assertEquals(0,ledger.size());
        var good=ledger.issue(PAIR,result,ContactCreditLedger.Source.SABLE_POST_EFFECT,
                List.of(new ContactCreditLedger.Region(a(0),b(0))));
        assertTrue(good.credited());
        var epoch=Pair.canonical("server-epoch-2","minecraft:overworld",
                new Owner("PLOT:alpha",1),new Owner("PLOT:beta",1));
        var epochGrant=ledger.issue(epoch,approved(3,epoch,
                        target(epoch.a().stableKey(),0),target(epoch.b().stableKey(),0)),
                ContactCreditLedger.Source.SABLE_POST_EFFECT,
                List.of(new ContactCreditLedger.Region(target(epoch.a().stableKey(),0),
                        target(epoch.b().stableKey(),0))));
        assertTrue(epochGrant.credited()); assertEquals(2,ledger.size());
        ledger.invalidateOwner(PAIR.a().stableKey());
        assertEquals(0,ledger.size());
    }
    @Test void staleCreditsAreCollectedWithoutNewQ() {
        var ledger=new ContactCreditLedger();
        ledger.issue(PAIR,approved(3,PAIR,a(0),b(0)),ContactCreditLedger.Source.SABLE_POST_EFFECT,
                List.of(new ContactCreditLedger.Region(a(0),b(0))));
        assertEquals(1,ledger.size());
        ledger.prune(603);assertEquals(1,ledger.size());
        ledger.prune(604);assertEquals(0,ledger.size());
    }
    @Test void distinctRearmedEpisodesCanIssueAgainButSameEpisodeCannot() {
        var f=new GameImpactCredit(new GameImpactCredit.Settings(2,2,0.25,64,2,4096,600));
        var l=new ContactCreditLedger();
        f.step(new Frame(PAIR,1,List.of(),true,false,null,null));
        f.step(new Frame(PAIR,2,List.of(),true,false,null,null));
        var first=f.step(new Frame(PAIR,3,SAMPLES,false,true,a(0),b(0)));
        assertTrue(l.issue(PAIR,first,ContactCreditLedger.Source.SABLE_POST_EFFECT,
                List.of(new ContactCreditLedger.Region(a(0),b(0)))).credited());
        for(int t=4;t<=23;t++)assertFalse(f.step(new Frame(PAIR,t,SAMPLES,false,true,a(0),b(0))).credited());
        f.step(new Frame(PAIR,24,List.of(),true,false,null,null));
        f.step(new Frame(PAIR,25,List.of(),true,false,null,null));
        var second=f.step(new Frame(PAIR,26,SAMPLES,false,true,a(0),b(0)));
        assertTrue(second.credited());assertNotEquals(first.episode(),second.episode());
        assertTrue(l.issue(PAIR,second,ContactCreditLedger.Source.SABLE_POST_EFFECT,
                List.of(new ContactCreditLedger.Region(a(0),b(0)))).credited());
        assertEquals(2,l.size());
    }
    @Test void callbackHintCannotMintCreditButSharedCauseIsDeduplicated() {
        var ledger=new ContactCreditLedger();
        var decision=approved(3,PAIR,a(0),b(0));
        var region=List.of(new ContactCreditLedger.Region(a(0),b(0)));
        var hint=ledger.issue(PAIR,decision,ContactCreditLedger.Source.SPECIAL_CALLBACK_HINT,region);
        assertEquals(ContactCreditLedger.Status.HINT_NOT_AUTHORITATIVE,hint.status());
        assertEquals(0,ledger.size());
        var authoritative=ledger.issue(PAIR,decision,ContactCreditLedger.Source.SABLE_POST_EFFECT,region);
        assertTrue(authoritative.credited());
        assertEquals(0.0,authoritative.availableQ());
        assertEquals(0.0,authoritative.spent());
        assertEquals(10.0,ledger.lookup(authoritative.id()).accounted());
        assertEquals(ContactCreditLedger.Status.DUPLICATE_CAUSE,
                ledger.issue(PAIR,decision,ContactCreditLedger.Source.SOLVER_VERIFIED,region).status());
    }

    @Test void sameOwnerLocalCellWithDifferentFingerprintsMustNotDoubleSpendQ() {
        var ledger=new ContactCreditLedger();
        var decision=approved(3,PAIR,a(0),b(0));
        var altered=new Target(PAIR.a().stableKey(),a(0).localBlock(),"minecraft:dirt");
        var rejected=ledger.issue(PAIR,decision,ContactCreditLedger.Source.SABLE_POST_EFFECT,
                List.of(new ContactCreditLedger.Region(a(0),b(0)),
                        new ContactCreditLedger.Region(altered,b(0))));
        assertEquals(ContactCreditLedger.Status.INVALID_TARGET_SET,rejected.status());
        assertEquals(0,ledger.size());
        assertEquals(0,rejected.issuedQ());
        var clean=ledger.issue(PAIR,decision,ContactCreditLedger.Source.SABLE_POST_EFFECT,
                List.of(new ContactCreditLedger.Region(a(0),b(0)),
                        new ContactCreditLedger.Region(a(0),b(0))));
        assertTrue(clean.credited());
        assertEquals(1,clean.a().size());
        assertEquals(1,clean.b().size());
        assertEquals(clean.issuedQ(),clean.accounted(),1e-9);
    }
    @Test void conflictingBCellFingerprintAlsoVetoesEntireIssue() {
        var ledger=new ContactCreditLedger();
        var decision=approved(3,PAIR,a(0),b(0));
        var changed=new Target(PAIR.b().stableKey(),b(0).localBlock(),"minecraft:dirt");
        var rejected=ledger.issue(PAIR,decision,ContactCreditLedger.Source.SABLE_POST_EFFECT,
                List.of(new ContactCreditLedger.Region(a(0),b(0)),
                        new ContactCreditLedger.Region(a(0),changed)));
        assertEquals(ContactCreditLedger.Status.INVALID_TARGET_SET,rejected.status());
        assertEquals(0,ledger.size());
    }
}
