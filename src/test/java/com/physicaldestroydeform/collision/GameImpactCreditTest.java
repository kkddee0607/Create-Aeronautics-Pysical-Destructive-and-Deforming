package com.physicaldestroydeform.collision;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.physicaldestroydeform.collision.GameImpactCredit.*;

/** Additional official JUnit tests once integrated into the original Gradle project. */
final class GameImpactCreditTest {
    private static final Owner A=new Owner("PLOT:1",1);
    private static final Owner B=new Owner("WORLD:minecraft:overworld",1);
    private static final Pair P=Pair.canonical("run","minecraft:overworld",A,B);
    private static final Target TA=new Target(P.a().stableKey(),"1,2,3","minecraft:stone");
    private static final Target TB=new Target(P.b().stableKey(),"4,5,6","minecraft:stone");
    private static final List<ObservedForce> MANY=List.of(
            new ObservedForce(-1,1,42,0),new ObservedForce(-1,1,42,1),
            new ObservedForce(-1,1,42,2),new ObservedForce(-1,1,42,3));
    private static Frame frame(long tick,List<ObservedForce> observed,
                               boolean separation,boolean approach,Target a,Target b) {
        return new Frame(P,tick,observed,separation,approach,a,b);
    }
    @Test void verificationSnapshotIsReadOnlyAndShowsPendingEvidenceConsumption() {
        var engine=new GameImpactCredit(Settings.provisional());
        assertEquals(Phase.UNINITIALIZED,engine.evidence(P).phase());
        engine.step(frame(1,List.of(),true,false,TA,TB));
        engine.step(frame(2,List.of(),true,false,TA,TB));
        assertEquals(2,engine.evidence(P).separatedSamples());
        var initial=engine.step(frame(3,MANY,false,true,null,null));
        assertEquals(Reason.TARGET_UNVERIFIED,initial.reason());
        var before=engine.evidence(P);
        assertEquals(Phase.ACTIVE,before.phase());
        assertTrue(before.hasUnspentAuthorization());
        assertEquals(3,before.authorizationTick());
        assertEquals(before,engine.evidence(P));
        var granted=engine.step(frame(4,MANY,false,false,TA,TB));
        var after=engine.evidence(P);
        assertEquals(Reason.APPROVED,granted.reason());
        assertFalse(after.pendingApproach());
        assertFalse(after.hasUnspentAuthorization());
        assertEquals(3,after.authorizationTick());
        assertEquals(1,after.episode());
        assertEquals(Reason.DUPLICATE_EPISODE,
                engine.step(frame(5,MANY,false,true,TA,TB)).reason());
    }
    @Test void defaultPolicyIsBoundedAndDocumented() {
        assertEquals(10.0,Settings.provisional().candidate(42));
        assertEquals(0.0,Settings.provisional().candidate(1.8));
        assertEquals(64.0,Settings.provisional().candidate(100000));
    }
    @Test void duplicateForcePointsProduceOneGroupPeak() {
        assertEquals(42.0,GameImpactCredit.peak(MANY));
        var e=new GameImpactCredit(Settings.provisional());
        e.step(frame(1,List.of(),true,false,TA,TB));
        e.step(frame(2,List.of(),true,false,TA,TB));
        var result=e.step(frame(3,MANY,false,true,TA,TB));
        assertEquals(10.0,result.issuedQ());
        assertEquals(5.0,result.allocation().sideA());
        assertEquals(5.0,result.allocation().sideB());
    }
    @Test void previousGapRemainsArmedUntilContactAndRearmsAfterTwoSeparations() {
        var e=new GameImpactCredit(Settings.provisional());
        assertEquals(Phase.UNINITIALIZED,e.phase(P));
        e.step(frame(1,List.of(),true,false,null,null));
        e.step(frame(2,List.of(),true,false,null,null));
        assertEquals(Phase.ARMED,e.phase(P));
        // A verified pre-contact gap at tick 2 can authorize the first impact
        // at tick 20, even when previousSeparated (last two ticks) is false.
        assertTrue(e.step(frame(20,MANY,false,true,TA,TB)).credited());
        assertEquals(Phase.ACTIVE,e.phase(P));
        assertFalse(e.step(frame(21,MANY,false,true,TA,TB)).credited());
        e.step(frame(22,List.of(),true,false,null,null));
        assertEquals(Phase.COOLING,e.phase(P));
        e.step(frame(23,List.of(),true,false,null,null));
        assertEquals(Phase.ARMED,e.phase(P));
        assertTrue(e.step(frame(24,MANY,false,true,TA,TB)).credited());
    }
    @Test void noNewCreditDuringTwentySustainedTicks() {
        var e=new GameImpactCredit(Settings.provisional());
        e.step(frame(1,List.of(),true,false,TA,TB));
        e.step(frame(2,List.of(),true,false,TA,TB));
        assertTrue(e.step(frame(3,MANY,false,true,TA,TB)).credited());
        for(int t=4;t<=24;t++)assertFalse(e.step(frame(t,MANY,false,true,TA,TB)).credited());
    }
    @Test void silenceDoesNotProveSeparation() {
        var e=new GameImpactCredit(Settings.provisional());
        e.step(frame(1,MANY,false,true,TA,TB));
        assertEquals(Reason.UNKNOWN_SEPARATION,
                e.step(frame(2,List.of(),false,false,TA,TB)).reason());
        assertEquals(Reason.DUPLICATE_EPISODE,
                e.step(frame(3,MANY,false,true,TA,TB)).reason());
    }
    @Test void requiresVerifiedApproachAndAtLeastOneRealTarget() {
        var e=new GameImpactCredit(Settings.provisional());
        e.step(frame(1,List.of(),true,false,TA,TB));
        e.step(frame(2,List.of(),true,false,TA,TB));
        assertEquals(Reason.APPROACH_UNVERIFIED,
                e.step(frame(3,MANY,false,false,TA,TB)).reason());
        e.step(frame(4,List.of(),true,false,TA,TB));
        e.step(frame(5,List.of(),true,false,TA,TB));
        assertEquals(Reason.TARGET_UNVERIFIED,
                e.step(frame(6,MANY,false,true,null,null)).reason());
    }
    @Test void oneVerifiedSideReservesHalf() {
        var e=new GameImpactCredit(Settings.provisional());
        e.step(frame(1,List.of(),true,false,TA,TB));
        e.step(frame(2,List.of(),true,false,TA,TB));
        var c=e.step(frame(3,MANY,false,true,TA,null));
        assertEquals(10.0,c.issuedQ());
        assertEquals(5.0,c.allocation().sideA());
        assertEquals(0.0,c.allocation().sideB());
        assertEquals(5.0,c.allocation().unused());
    }
    @Test void malformedNativeDataIsNeverPromoted() {
        assertTrue(GameImpactCredit.parse(new double[14]).isEmpty());
        double[] arr=new double[15];arr[0]=3;arr[1]=-1;arr[2]=Double.NaN;
        assertTrue(GameImpactCredit.parse(arr).isEmpty());
        arr[2]=42;assertEquals(1,GameImpactCredit.parse(arr).size());
    }
    @Test void collisionLocalPointRequiresVerifiedTransform() {
        var resolver=OwnerLocalTargetCandidates.provisional();
        var input=new OwnerLocalTargetCandidates.Request("PLOT:1",true,null,false,
                new OwnerLocalTargetCandidates.Point(10.5,64.5,10.5),false,
                null,false,List.of());
        var inspector=(OwnerLocalTargetCandidates.Inspector)(owner,pos)->
                new OwnerLocalTargetCandidates.Evidence(owner,pos,"minecraft:stone",true,true,true,false);
        assertFalse(resolver.resolve(input,inspector).verified());
    }
    @Test void directCallbackTargetCanBeValidatedWithoutNativeGeometry() {
        var resolver=OwnerLocalTargetCandidates.provisional();
        var location=new OwnerLocalTargetCandidates.Block(10,64,10);
        var input=new OwnerLocalTargetCandidates.Request("PLOT:1",true,location,true,
                null,false,null,false,List.of());
        var inspector=(OwnerLocalTargetCandidates.Inspector)(owner,pos)->
                new OwnerLocalTargetCandidates.Evidence(owner,pos,"minecraft:stone",true,true,true,false);
        assertTrue(resolver.resolve(input,inspector).verified());
    }
    @Test void invalidateOwnerGenerationCannotReuseOldArmedState() {
        var e=new GameImpactCredit(Settings.provisional());
        e.step(frame(1,List.of(),true,false,TA,TB));
        e.step(frame(2,List.of(),true,false,TA,TB));
        assertEquals(Phase.ARMED,e.phase(P));
        e.invalidateOwner(P.a().stableKey());
        assertEquals(Phase.UNINITIALIZED,e.phase(P));
        assertEquals(Reason.INITIAL_CONTACT_UNKNOWN,e.step(frame(3,MANY,false,true,TA,TB)).reason());
    }
    @Test void risingEdgeNeedsQuietEvidenceAndIndependentGeometry() {
        var e=new GameImpactCredit(Settings.provisional());
        assertEquals(1.8,e.settings().risingRatio());
        assertEquals(2,e.settings().quietFrames());
        assertFalse(e.risingEdge(10,30,2,false));
        assertFalse(e.risingEdge(10,12,2,true));
        assertFalse(e.risingEdge(10,30,1,true));
        assertTrue(e.risingEdge(10,18,2,true));
    }
    @Test void activeApprovalUsesEarlierArmedEvidenceWithinTwoFrameBurst() {
        var e=new GameImpactCredit(Settings.provisional());
        e.step(frame(1,List.of(),true,false,null,null));
        e.step(frame(2,List.of(),true,false,null,null));
        var first=e.step(frame(3,MANY,false,true,null,null));
        assertEquals(Reason.TARGET_UNVERIFIED,first.reason());
        assertEquals(Phase.ACTIVE,first.phase());
        assertEquals(1,first.episodeGeneration());
        var delayed=e.step(frame(4,MANY,false,false,TA,TB));
        assertTrue(delayed.credited());
        assertEquals(3,delayed.authorizationTick());
        assertEquals(1,delayed.episodeGeneration());
        assertFalse(e.step(frame(5,MANY,false,true,TA,TB)).credited());
        e.step(frame(6,List.of(),true,false,null,null));
        e.step(frame(7,List.of(),true,false,null,null));
        var repeat=e.step(frame(8,MANY,false,true,TA,TB));
        assertTrue(repeat.credited());
        assertEquals(2,repeat.episodeGeneration());
    }
}
