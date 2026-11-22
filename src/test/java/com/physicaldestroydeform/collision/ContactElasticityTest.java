package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.MaterialProperties;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 0.6.1 direct-contact F debit, before local propagation and own damage. */
class ContactElasticityTest {
    private static final GameImpactCredit.Pair PAIR=GameImpactCredit.Pair.canonical("run","minecraft:overworld",
            new GameImpactCredit.Owner("PLOT:A",1),new GameImpactCredit.Owner("PLOT:B",2));
    private static final GameImpactCredit.Settings FORCES=GameImpactCredit.Settings.provisional();
    private static final DamageModel.Settings DAMAGE=DamageModel.Settings.defaults();
    private static final GameImpactCredit.Target A=target(PAIR.a(),0), B=target(PAIR.b(),0),
            A1=target(PAIR.a(),1), B1=target(PAIR.b(),1);
    private static GameImpactCredit.Target target(GameImpactCredit.Owner owner,int x){
        return new GameImpactCredit.Target(owner.stableKey(),x+",0,0","minecraft:stone");
    }
    private static MaterialProperties material(int e){return new MaterialProperties(0,1024,0,e,false);}
    private static GameImpactCredit.Decision decision(){
        var gate=new GameImpactCredit(FORCES);
        gate.step(new GameImpactCredit.Frame(PAIR,1,List.of(),true,false,null,null));
        gate.step(new GameImpactCredit.Frame(PAIR,2,List.of(),true,false,null,null));
        var result=gate.step(new GameImpactCredit.Frame(PAIR,3,
                List.of(new GameImpactCredit.ObservedForce(1,2,200,0)),false,true,A,B));
        assertTrue(result.credited());
        return result;
    }
    private static ContactCreditLedger.Grant issue(ContactCreditLedger ledger,List<ContactCreditLedger.Region> contacts,
                                                   Function<GameImpactCredit.Target,MaterialProperties> materials){
        return ledger.issuePropagatedTargets(PAIR,decision(),ContactCreditLedger.Source.SABLE_POST_EFFECT,
                contacts,(from,x,y,z)->ForcePropagation.Probe.empty(),materials,FORCES,DAMAGE,
                Map.of(),null,fp->material(0));
    }
    private static ContactCreditLedger.Grant direct(int a,int b){
        var result=issue(new ContactCreditLedger(),List.of(new ContactCreditLedger.Region(A,B)),
                t->material(t.equals(A)?a:b));
        assertTrue(result.credited());
        assertLedger(result);
        return result;
    }
    private static double f(ForcePropagation.Result result,GameImpactCredit.Target target){
        return result.shares().stream().filter(s->s.target().equals(target)).mapToDouble(ForcePropagation.Share::allocatedF).sum();
    }
    private static void assertLedger(ContactCreditLedger.Grant grant){
        assertEquals(200,grant.inputF(),1e-8);
        for(var side:List.of(grant.propagationA(),grant.propagationB()))
            assertEquals(side.inputF(),side.assignedF()+side.contactDampedF()+side.dampedF()+side.unresolvedF()
                    +side.audit().thresholdDiscardF(),1e-8);
        assertEquals(grant.issuedQ(),grant.accounted(),1e-8);
    }
    @Test void zeroElasticityPreservesOriginalF(){
        var grant=direct(0,0);
        assertEquals(100,f(grant.propagationA(),A),1e-8);
        assertEquals(100,f(grant.propagationB(),B),1e-8);
        assertEquals(0,grant.contactDampedF(),1e-8);
    }
    @Test void oneElasticBodyProtectsOpposingBodyNotItsOwnInitialF(){
        var grant=direct(100,0);
        assertEquals(100,f(grant.propagationA(),A),1e-8);
        assertEquals(66,f(grant.propagationB(),B),1e-8);
        assertEquals(34,grant.contactDampedF(),1e-8);
    }
    @Test void bothElasticBodiesDebitInitialFExactlyOnce(){
        var grant=direct(100,100);
        assertEquals(66,f(grant.propagationA(),A),1e-8);
        assertEquals(66,f(grant.propagationB(),B),1e-8);
        assertEquals(68,grant.contactDampedF(),1e-8);
        var half=direct(50,50);
        assertEquals(100*DamageModel.contactElasticityTransmissionPercent(50,DAMAGE),f(half.propagationA(),A),1e-8);
    }
    @Test void multipleMaterialsAndDuplicateContactRespectDistinctPerTargetShares(){
        var c1=new ContactCreditLedger.Region(A,B);
        var c2=new ContactCreditLedger.Region(A,B1);
        var ledger=new ContactCreditLedger();
        var grant=issue(ledger,List.of(c1,c1,c2),t->material(t.equals(B1)?100:0));
        assertTrue(grant.credited());
        assertLedger(grant);
        assertEquals(83,f(grant.propagationA(),A),1e-8);
        assertEquals(50,f(grant.propagationB(),B),1e-8);
        assertEquals(50,f(grant.propagationB(),B1),1e-8);
        assertEquals(17,grant.contactDampedF(),1e-8);
        assertEquals(ContactCreditLedger.Status.DUPLICATE_CAUSE,issue(ledger,List.of(c1,c2),t->material(0)).status());
    }
    @Test void unpairedBlockNeverSuppliesOpposingElasticity(){
        var grant=issue(new ContactCreditLedger(),List.of(new ContactCreditLedger.Region(A,B),
                new ContactCreditLedger.Region(A1,null)),t->material(t.equals(A1)?100:0));
        assertTrue(grant.credited());
        assertLedger(grant);
        assertEquals(100,f(grant.propagationB(),B),1e-8);
        assertEquals(50,f(grant.propagationA(),A),1e-8);
        assertEquals(50,grant.propagationA().unresolvedF(),1e-8);
        assertEquals(0,grant.contactDampedF(),1e-8);
    }
    @Test void unknownOpposingMaterialWithholdsBudgetNotAssumingRigidOrElastic(){
        var grant=issue(new ContactCreditLedger(),List.of(new ContactCreditLedger.Region(A,B),
                new ContactCreditLedger.Region(A1,B1)),t->t.equals(B1)?null:material(0));
        assertTrue(grant.credited());
        assertLedger(grant);
        assertEquals(50,f(grant.propagationA(),A),1e-8);
        assertEquals(50,grant.propagationA().unresolvedF(),1e-8);
        assertEquals(50,grant.propagationB().unresolvedF(),1e-8);
        assertEquals(0,grant.contactDampedF(),1e-8);
    }
    @Test void mixedProvedAndUnpairedWitnessDoesNotReissueUnknownShare(){
        var grant=issue(new ContactCreditLedger(),List.of(new ContactCreditLedger.Region(A,B),
                new ContactCreditLedger.Region(A,null)),t->material(0));
        assertTrue(grant.credited());
        assertLedger(grant);
        assertEquals(50,f(grant.propagationA(),A),1e-8);
        assertEquals(50,grant.propagationA().unresolvedF(),1e-8);
        assertEquals(100,f(grant.propagationB(),B),1e-8);
    }
    @Test void completeUniformCandidateSetsRecoverAmbiguousContactWithoutGuessingIdentity(){
        var grant=issue(new ContactCreditLedger(),List.of(
                new ContactCreditLedger.Region(A,null,List.of(B,B1)),
                new ContactCreditLedger.Region(null,B,List.of(A)),
                new ContactCreditLedger.Region(null,B1,List.of(A))),
                t->material(t.ownerKey().equals(B.ownerKey())?100:0));
        assertTrue(grant.credited());assertLedger(grant);
        assertEquals(66,f(grant.propagationA(),A),1e-8);
        assertEquals(50,f(grant.propagationB(),B),1e-8);
        assertEquals(50,f(grant.propagationB(),B1),1e-8);
        assertEquals(0,grant.unresolvedF(),1e-8);
    }
    @Test void conflictingOrMissingCandidateMaterialNeverGuessesAnElasticity(){
        var regions=List.of(new ContactCreditLedger.Region(A,null,List.of(B,B1)),
                new ContactCreditLedger.Region(null,B,List.of(A)),
                new ContactCreditLedger.Region(null,B1,List.of(A)));
        for(var unknown:List.of(false,true)){
            var grant=issue(new ContactCreditLedger(),regions,t->t.equals(B1)?unknown?null:material(100):material(0));
            assertTrue(grant.credited());assertLedger(grant);
            assertEquals(100,grant.propagationA().unresolvedF(),1e-8);
            assertEquals(0,f(grant.propagationA(),A),1e-8);
        }
    }
    @Test void repeatedEquivalentDirectAndCandidateEvidenceDoesNotSplitExtraShares(){
        var grant=issue(new ContactCreditLedger(),List.of(new ContactCreditLedger.Region(A,B),
                new ContactCreditLedger.Region(A,null,List.of(B)),new ContactCreditLedger.Region(A,B1)),
                t->material(t.equals(B1)?100:0));
        assertTrue(grant.credited());assertLedger(grant);
        assertEquals(83,f(grant.propagationA(),A),1e-8);
    }
    @Test void candidateIdentityMustBelongToVerifiedOppositeOwnerAndSeedCoverage(){
        var ledger=new ContactCreditLedger();
        var grant=issue(ledger,List.of(new ContactCreditLedger.Region(A,null,List.of(B1)),
                new ContactCreditLedger.Region(null,B)),t->material(0));
        assertEquals(ContactCreditLedger.Status.INVALID_TARGET_SET,grant.status());
        assertEquals(0,ledger.size());
    }
    @Test void nonfiniteInitialBudgetRejectedWithoutCredit(){
        var good=decision();
        for(double bad:List.of(Double.NaN,Double.POSITIVE_INFINITY)){
            var corrupt=new GameImpactCredit.Decision(good.phase(),good.reason(),bad,good.issuedQ(),good.allocation(),
                    good.episode(),good.episodeGeneration(),good.authorizationTick(),good.tick(),good.policyRevision());
            var ledger=new ContactCreditLedger();
            var grant=ledger.issuePropagatedTargets(PAIR,corrupt,ContactCreditLedger.Source.SABLE_POST_EFFECT,
                    List.of(new ContactCreditLedger.Region(A,B)),(from,x,y,z)->ForcePropagation.Probe.empty(),
                    t->material(0),FORCES,DAMAGE,Map.of(),null,fp->material(0));
            assertEquals(ContactCreditLedger.Status.INVALID_TARGET_SET,grant.status());
            assertEquals(0,ledger.size());
        }
    }
    @Test void staleGenerationFromCrossContactGraphRejectsPlan(){
        var graph=new ForcePropagation.CrossOwnerContext(){
            @Override public Map<String,GameImpactCredit.Owner> owners(){
                return Map.of(PAIR.a().stableKey(),new GameImpactCredit.Owner(PAIR.a().stableKey(),PAIR.a().generation()+1));
            }
            @Override public List<ForcePropagation.CrossEdge> edges(GameImpactCredit.Target from,ForcePropagation.Orientation inward){
                return List.of();
            }
        };
        var ledger=new ContactCreditLedger();
        var grant=ledger.issuePropagatedTargets(PAIR,decision(),ContactCreditLedger.Source.SABLE_POST_EFFECT,
                List.of(new ContactCreditLedger.Region(A,B)),(from,x,y,z)->ForcePropagation.Probe.empty(),
                t->material(0),FORCES,DAMAGE,Map.of(),null,fp->material(0),graph);
        assertEquals(ContactCreditLedger.Status.INVALID_TARGET_SET,grant.status());
        assertEquals(0,ledger.size());
    }
    @Test void ambiguousThirdOwnerDoesNotCancelOriginalDirectSides(){
        var thirdOwner=new GameImpactCredit.Owner("PLOT:C",3);var third=target(thirdOwner,0);
        var direction=new ForcePropagation.Orientation(new FrameMath.Vec(1,0,0),"TEST");
        var graph=new ForcePropagation.CrossOwnerContext(){
            public Map<String,GameImpactCredit.Owner> owners(){return Map.of(thirdOwner.stableKey(),thirdOwner);}
            public List<ForcePropagation.CrossEdge> edges(GameImpactCredit.Target from,ForcePropagation.Orientation inward){
                return from.equals(A)||from.equals(B)?List.of(new ForcePropagation.CrossEdge(third,direction,1)):List.of();
            }
        };
        var grant=new ContactCreditLedger().issuePropagatedTargets(PAIR,decision(),ContactCreditLedger.Source.SABLE_POST_EFFECT,
                List.of(new ContactCreditLedger.Region(A,B)),(from,x,y,z)->ForcePropagation.Probe.empty(),t->material(0),FORCES,DAMAGE,
                Map.of(A.ownerKey(),direction,B.ownerKey(),direction),null,fp->material(0),graph);
        assertTrue(grant.credited());assertLedger(grant);
        assertEquals(50,f(grant.propagationA(),A),1e-9);assertEquals(50,f(grant.propagationB(),B),1e-9);
        assertEquals(100,grant.unresolvedF(),1e-9);assertTrue(grant.additionalOwners().isEmpty());
    }
}
