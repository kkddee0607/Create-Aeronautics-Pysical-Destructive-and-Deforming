package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.MaterialProperties;
import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OriginLastFixContactTest {
    static final String DIM="minecraft:overworld";
    static final UUID ID=UUID.fromString("12345678-1234-1234-1234-123456789abc");
    static final GameImpactCredit.Pair PAIR=GameImpactCredit.Pair.canonical("originlast",DIM,
            new GameImpactCredit.Owner("PLOT:"+ID,1),new GameImpactCredit.Owner("WORLD:"+DIM,1));
    static GameImpactCredit.Target target(int side,int x){return new GameImpactCredit.Target(
            (side==0?PAIR.a():PAIR.b()).stableKey(),x+",0,0","minecraft:stone");}
    static NativeCollisionTelemetry.Contact witness(double x){
        var zero=new FrameMath.Vec(0,0,0);
        var pose=new FrameMath.Pose(zero,zero,new FrameMath.Vec(1,1,1),0,0,0,1);
        return new NativeCollisionTelemetry.Contact(7,-1,1000,.707,.707,0,0,-1,0,
                x,.5,.5,x+.2,.5,.5,DIM,9,new NativeCollisionTelemetry.OwnerFrame(ID,pose),null);
    }
    @Test void directTargetDoesNotPromoteAdjacentCandidates(){
        var result=SableContactRegions.collect(PAIR,List.of(witness(.5)),(c,owner,budget)->{
            int side=owner.equals(PAIR.a().stableKey())?0:1;
            return new SableContactRegions.Check(List.of(target(side,0),target(side,1)),2,false,"R2","UNIQUE",target(side,0));
        });
        assertEquals(List.of(new ContactCreditLedger.Region(target(0,0),target(1,0))),result.regions());
        assertEquals(0,result.reservedA());assertEquals(0,result.reservedB());
    }
    @Test void ambiguousSelfIsNotADamageSeedButOppositeCanUseConsensus(){
        var result=SableContactRegions.collect(PAIR,List.of(witness(.5)),(c,owner,budget)->
                owner.equals(PAIR.a().stableKey())?new SableContactRegions.Check(List.of(target(0,0),target(0,1)),2)
                        :new SableContactRegions.Check(List.of(target(1,0)),1));
        assertNull(result.firstA());assertEquals(2,result.reservedA());
        assertEquals(List.of(new ContactCreditLedger.Region(null,target(1,0),List.of(target(0,0),target(0,1)))),result.regions());
    }
    @Test void repeatedAmbiguousCandidatesReserveDistinctCellsOnly(){
        var c=witness(.5);
        var result=SableContactRegions.collect(PAIR,List.of(c,c,c),(r,owner,budget)->
                owner.equals(PAIR.a().stableKey())?new SableContactRegions.Check(List.of(target(0,0),target(0,1)),2)
                        :new SableContactRegions.Check(List.of(target(1,0)),1));
        assertEquals(2,result.reservedA());assertEquals(1,result.regions().size());
    }
    @Test void unresolvedNativeEvidenceIsNotErasedByUnrelatedSurface(){
        var nativeRegion=new ContactCreditLedger.Region(target(0,0),null);
        var merged=SableContactRegions.mergeBilateralPairs(List.of(nativeRegion),
                List.of(new BilateralContactSurface.Match(target(0,0),target(1,1))));
        assertTrue(merged.contains(nativeRegion));
    }
    @Test void certifiedPairDoesNotDuplicateAlreadyCoveredOpponent(){
        var a=target(0,0);var b=target(1,0);var c=target(1,1);
        var existing=new ContactCreditLedger.Region(a,null,List.of(b,c));
        var merged=SableContactRegions.mergeBilateralPairs(List.of(existing),List.of(new BilateralContactSurface.Match(a,b)));
        assertEquals(2,merged.size());assertTrue(merged.contains(existing));
        assertTrue(merged.contains(new ContactCreditLedger.Region(null,b,List.of(a))));
    }
    @Test void materialOnlyCandidatesAndReservedFKeepBudgetConserved(){
        var policy=new GameImpactCredit(GameImpactCredit.Settings.provisional());
        policy.step(new GameImpactCredit.Frame(PAIR,1,List.of(),true,false,null,null));
        policy.step(new GameImpactCredit.Frame(PAIR,2,List.of(),true,false,null,null));
        var decision=policy.step(new GameImpactCredit.Frame(PAIR,3,List.of(new GameImpactCredit.ObservedForce(7,-1,1000,0)),false,true,target(0,0),target(1,0)));
        assertTrue(decision.credited());
        var regions=List.of(new ContactCreditLedger.Region(target(0,0),target(1,0)),
                new ContactCreditLedger.Region(null,target(1,0),List.of(target(0,1),target(0,2))));
        var material=new MaterialProperties(10,100,0,0,false);
        var grant=new ContactCreditLedger().issueContactSeeds(PAIR,decision,ContactCreditLedger.Source.SABLE_POST_EFFECT,
                regions,2,0,(from,x,y,z)->ForcePropagation.Probe.empty(),t->material,
                policy.settings(),DamageModel.Settings.defaults(),Map.of(),null,null);
        assertTrue(grant.credited());
        assertEquals(decision.peakForce()/2*2/3,grant.propagationA().directUnresolvedF(),1e-7);
        assertEquals(0,grant.propagationB().directUnresolvedF(),1e-7);
        assertEquals(1,grant.propagationA().shares().size());
        assertEquals(1,grant.propagationB().shares().size());
        assertEquals(grant.issuedQ(),grant.accounted(),1e-6);
    }
    @Test void materialOnlyCandidateRequiresCurrentFingerprint(){
        var cell=target(0,1);
        assertTrue(OwnerResolver.matchesSnapshot((from,x,y,z)->ForcePropagation.Probe.present(cell),cell));
        assertFalse(OwnerResolver.matchesSnapshot((from,x,y,z)->ForcePropagation.Probe.present(target(0,2)),cell));
        assertFalse(OwnerResolver.matchesSnapshot((from,x,y,z)->ForcePropagation.Probe.unknown(),cell));
    }
    @Test void directNormalIsIndependentOfWorldWitnessDistanceAndPrePose(){
        var c=witness(.5);
        var a=SableConsumerTargets.identityAxis(c,true);var b=SableConsumerTargets.identityAxis(c,false);
        assertEquals(-1,a.x());assertEquals(-1,a.y());assertEquals(0,a.z());
        assertEquals(0,b.x());assertEquals(1,b.y());assertEquals(0,b.z());
        assertFalse(TargetDirections.forEvent(PAIR,List.of(c),List.of(),9,true).active());
    }
    @Test void optionalExpansionRunsOnlyWhenRequestedAndAtMostOnce(){
        var direct=SableContactRegions.collect(PAIR,List.of(witness(.5)),(c,o,n)->
                new SableContactRegions.Check(List.of(target(o.equals(PAIR.a().stableKey())?0:1,0)),1));
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var prepared=new SableContactRegions.Prepared(direct,()->{calls.incrementAndGet();return direct;});
        assertEquals(0,calls.get());assertEquals(direct,prepared.direct);
        assertEquals(direct,prepared.expand());assertEquals(direct,prepared.expand());assertEquals(1,calls.get());
    }
    @Test void previouslyCertifiedExpansionCannotByItselfBecomeNewRegionalImpact(){
        var a=target(0,0);var b=target(1,0);var x=target(0,1);var y=target(1,1);
        var direct=List.of(new ContactCreditLedger.Region(a,b));
        var expanded=SableContactRegions.mergeBilateralPairs(direct,List.of(new BilateralContactSurface.Match(x,y)));
        var history=RegionalContactProof.capture(expanded);
        assertFalse(RegionalContactProof.hasNewCells(history,List.of(new ContactCreditLedger.Region(x,y))));
        assertTrue(RegionalContactProof.hasNewCells(history,List.of(new ContactCreditLedger.Region(target(0,2),y))));
    }
}
