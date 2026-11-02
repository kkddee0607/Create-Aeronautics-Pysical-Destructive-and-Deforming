package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static com.physicaldestroydeform.collision.GameImpactCredit.*;

final class SableContactRegionsTest {
    private static final UUID PLOT=UUID.fromString("00000000-0000-0000-0000-000000000007");
    private static final Pair PAIR=Pair.canonical("run","minecraft:overworld",
            new Owner("PLOT:"+PLOT,1),new Owner("WORLD:minecraft:overworld",1));
    private static NativeCollisionTelemetry.Contact contact(double x) {
        var zero=new FrameMath.Vec(0,0,0);
        var unit=new FrameMath.Vec(1,1,1);
        var frame=new NativeCollisionTelemetry.OwnerFrame(PLOT,
                new FrameMath.Pose(zero,zero,unit,0,0,0,1));
        return new NativeCollisionTelemetry.Contact(7,-1,42,0,0,0,0,0,0,
                x,0,0,x,0,0,"minecraft:overworld",9,frame,null);
    }
    private static Target target(String owner,int i) {
        return new Target(owner,i+",0,0","minecraft:stone");
    }
    @Test void multiPointSingleRegionIsDeduplicated() {
        var c=contact(1);
        var result=SableContactRegions.collect(PAIR,List.of(c,c,c,c),
                (e,owner,remaining)->new SableContactRegions.Check(List.of(target(owner,1)),1));
        assertTrue(result.verified());assertEquals(1,result.regions().size());
        assertEquals(4,result.checksA());assertEquals(4,result.checksB());
    }
    @Test void twoPhysicalRegionsStayDistinctWithoutIssuingQ() {
        var result=SableContactRegions.collect(PAIR,List.of(contact(1),contact(2)),
                (e,owner,remaining)->new SableContactRegions.Check(List.of(target(owner,(int)e.contactAX())),1));
        assertEquals(2,result.regions().size());
        assertEquals(SableContactRegions.Status.PROVED,result.status());
    }
    @Test void fifthTargetIsProvedWithoutExpandingShapeBudget() {
        var points=new ArrayList<NativeCollisionTelemetry.Contact>();
        for(int i=0;i<5;i++)points.add(contact(i));
        var result=SableContactRegions.collect(PAIR,points,
                (e,owner,remaining)->new SableContactRegions.Check(List.of(target(owner,(int)e.contactAX())),1));
        assertEquals(SableContactRegions.Status.PROVED,result.status());
        assertEquals(5,result.regions().size());
        assertEquals(5,result.checksA());assertEquals(5,result.checksB());
    }
    @Test void incompleteSideQuarantinesOnlyThatSide() {
        var points=new ArrayList<NativeCollisionTelemetry.Contact>();
        for(int i=0;i<6;i++)points.add(contact(i));
        var result=SableContactRegions.collect(PAIR,points,
                (record,owner,remaining)->owner.equals(PAIR.a().stableKey())
                    ? new SableContactRegions.Check(List.of(target(owner,1)),1)
                    : ((int)record.contactAX()>=4
                        ? new SableContactRegions.Check(List.of(),0,true,"UNVERIFIED","CELL_BUDGET")
                        : new SableContactRegions.Check(List.of(target(owner,(int)record.contactAX())),1)));
        assertEquals(SableContactRegions.Status.PARTIAL,result.status());
        assertEquals(SableContactRegions.AuditFailure.SIDE_B_INCOMPLETE,result.trace().failure());
        assertEquals(1,result.regions().size());
        assertNotNull(result.firstA());
        assertNull(result.firstB());
        assertTrue(result.trace().detail().contains("overflowB=true"));
    }
    @Test void partialIncompleteSideNeverMintsThatSideAllocation() {
        var points=new ArrayList<NativeCollisionTelemetry.Contact>();
        for(int i=0;i<6;i++)points.add(contact(i));
        var result=SableContactRegions.collect(PAIR,points,
                (record,owner,remaining)->owner.equals(PAIR.a().stableKey())
                    ? new SableContactRegions.Check(List.of(target(owner,1)),1)
                    : ((int)record.contactAX()>=4
                        ? new SableContactRegions.Check(List.of(),0,true,"UNVERIFIED","CELL_BUDGET")
                        : new SableContactRegions.Check(List.of(target(owner,(int)record.contactAX())),1)));
        var policy=new GameImpactCredit(GameImpactCredit.Settings.provisional());
        policy.step(new GameImpactCredit.Frame(PAIR,1,List.of(),true,false,null,null));
        policy.step(new GameImpactCredit.Frame(PAIR,2,List.of(),true,false,null,null));
        var decision=policy.step(new GameImpactCredit.Frame(PAIR,3,
                List.of(new GameImpactCredit.ObservedForce(1,2,42,0)),false,true,result.firstA(),result.firstB()));
        assertTrue(decision.credited());
        var grant=new ContactCreditLedger().issue(PAIR,decision,
                ContactCreditLedger.Source.SABLE_POST_EFFECT,result.regions());
        assertEquals(ContactCreditLedger.Status.ISSUED,grant.status());
        assertEquals(5.0,grant.allocatedA());
        assertEquals(0.0,grant.allocatedB());
        assertEquals(5.0,grant.unused());
    }
    @Test void regionCardinalityWithinExpandedCapStaysDistinct() {
        var input=new ArrayList<NativeCollisionTelemetry.Contact>();
        for(int i=0;i<17;i++)input.add(contact(i));
        var result=SableContactRegions.collect(PAIR,input,(record,owner,remaining)-> {
            int index=(int)record.contactAX();
            if(index<16)return new SableContactRegions.Check(List.of(
                    target(owner,owner.equals(PAIR.a().stableKey())?index/4:index%4)),1);
            return new SableContactRegions.Check(List.of(target(owner,0),target(owner,1)),1);
        });
        assertTrue(result.verified());
        assertEquals(20,result.regions().size());
        assertTrue(result.trace().detail().contains("compactedRegions=false"));
    }
    @Test void thirtyTwoUniquePerSideStillProduceBoundedRegions() {
        var input=new ArrayList<NativeCollisionTelemetry.Contact>();
        for(int i=0;i<32;i++)input.add(contact(i));
        var result=SableContactRegions.collect(PAIR,input,
                (record,owner,remaining)->new SableContactRegions.Check(
                        List.of(target(owner,(int)record.contactAX())),1));
        assertEquals(SableContactRegions.Status.PROVED,result.status());
        assertEquals(32,result.regions().size());
        assertEquals(32,result.checksA());assertEquals(32,result.checksB());
    }
    @Test void thirtyThirdDistinctCellQuarantinesOnlyExhaustedOwner() {
        var points=new ArrayList<NativeCollisionTelemetry.Contact>();
        for(int i=0;i<33;i++)points.add(contact(i));
        var result=SableContactRegions.collect(PAIR,points,(record,owner,remaining)-> {
            if(owner.equals(PAIR.a().stableKey()))
                return remaining==0
                        ?new SableContactRegions.Check(List.of(),0,true,"UNVERIFIED","CELL_BUDGET_A")
                        :new SableContactRegions.Check(List.of(target(owner,(int)record.contactAX())),1);
            return new SableContactRegions.Check(List.of(target(owner,0)),0);
        });
        assertEquals(SableContactRegions.Status.PARTIAL,result.status());
        assertEquals(SableContactRegions.AuditFailure.SIDE_A_INCOMPLETE,result.trace().failure());
        assertEquals(32,result.checksA());
        assertEquals(0,result.checksB());
        assertNull(result.firstA());
        assertNotNull(result.firstB());
        assertEquals(1,result.regions().size());
    }
    @Test void missingPlotFrameQuarantinesOnlyPlotSide() {
        var bad=new NativeCollisionTelemetry.Contact(7,-1,42,0,0,0,0,0,0,
                0,0,0,10,0,0,"minecraft:overworld",9);
        var result=SableContactRegions.collect(PAIR,List.of(bad),
                (e,owner,remaining)->new SableContactRegions.Check(List.of(target(owner,1)),1));
        assertEquals(SableContactRegions.Status.PARTIAL,result.status());
        assertEquals(0,result.rejected());
        assertNull(result.firstA());
        assertNotNull(result.firstB());
    }
    @Test void multipleVerifiedBlocksInOneSourceContactPreserveBothSidesWithoutInventedPairs() {
        var result=SableContactRegions.collect(PAIR,List.of(contact(1)),
                (e,owner,remaining)->new SableContactRegions.Check(
                        List.of(target(owner,1),target(owner,2)),2));
        assertEquals(SableContactRegions.Status.PROVED,result.status());
        assertEquals(4,result.regions().size());
        assertEquals(2,result.checksA());
        assertEquals(2,result.checksB());
        assertTrue(result.regions().stream().allMatch(r->(r.a()==null)!=(r.b()==null)));
    }
    @Test void verificationTraceDistinguishesTargetLimitFromCellBudget() {
        var many=new ArrayList<NativeCollisionTelemetry.Contact>();
        for(int i=0;i<33;i++)many.add(contact(i));
        var overTarget=SableContactRegions.collect(PAIR,many,
                (contact,owner,remaining)->
                        remaining==0?new SableContactRegions.Check(List.of(),0,true,"UNVERIFIED","CELL_BUDGET")
                                :new SableContactRegions.Check(List.of(target(owner,(int)contact.contactAX())),1));
        assertEquals(SableContactRegions.AuditFailure.SIDE_A_AND_B_INCOMPLETE,
                overTarget.trace().failure());
        assertEquals(32,overTarget.trace().sourceIndex());
        assertEquals(32,overTarget.checksA());
        var overCells=SableContactRegions.collect(PAIR,List.of(contact(1)),
                (contact,owner,remaining)->new SableContactRegions.Check(
                        List.of(),0,true,"UNVERIFIED","R2_CELL_BUDGET planned=8 remaining=0"));
        assertEquals(SableContactRegions.AuditFailure.SIDE_A_AND_B_INCOMPLETE,
                overCells.trace().failure());
        assertTrue(overCells.trace().detail().contains("R2_CELL_BUDGET"));
    }
    @Test void verificationTraceExplainsUncorroboratedSource() {
        var invalid=new NativeCollisionTelemetry.Contact(7,-1,42,0,0,0,0,0,0,
                Double.NaN,0,0,15,0,0,"minecraft:overworld",9);
        var result=SableContactRegions.collect(PAIR,List.of(invalid),
                (contact,owner,remaining)->new SableContactRegions.Check(List.of(),0));
        assertEquals(SableContactRegions.AuditFailure.UNCORROBORATED,
                result.trace().failure());
        assertTrue(result.trace().detail().contains("invalidSourceRecords=1"));
    }
    @Test void duplicateAuditIsExplicitlyNotAShapeFailure() {
        var result=SableContactRegions.skippedDuplicate();
        assertEquals(SableContactRegions.Status.SKIPPED_DUPLICATE,result.status());
        assertEquals(0,result.checksA());assertEquals(0,result.checksB());
        assertFalse(result.verified());
    }
    @Test void separatedWitnessesAreStillAuditedIndependently() {
        var f=new NativeCollisionTelemetry.OwnerFrame(PLOT,new FrameMath.Pose(
                new FrameMath.Vec(0,0,0),new FrameMath.Vec(0,0,0),
                new FrameMath.Vec(1,1,1),0,0,0,1));
        var c=new NativeCollisionTelemetry.Contact(7,-1,42,1,0,0,-1,0,0,
                0.5,0,0,2.5,0,0,"minecraft:overworld",9,f,null);
        var res=SableContactRegions.collect(PAIR,List.of(c),
                (e,owner,remaining)->new SableContactRegions.Check(List.of(target(owner,1)),1));
        assertEquals(SableContactRegions.Status.PROVED,res.status());
        assertNotNull(res.firstA());assertNotNull(res.firstB());
        assertEquals(0,res.rejected());
        assertTrue(res.trace().detail().contains("witnessSeparated=1"));
        assertTrue(res.trace().detail().contains("frameReason=WITNESS_SEPARATED_GT_0.25"));
    }
    @Test void overBudgetBlocksPartialCredit() {
        var result=SableContactRegions.collect(PAIR,List.of(contact(1)),
                (e,owner,remaining)->new SableContactRegions.Check(List.of(target(owner,1)),16));
        // one source point is within the 32-check bound
        assertTrue(result.verified());
        var tooWide=SableContactRegions.collect(PAIR,List.of(contact(1),contact(2),contact(3)),
                (e,owner,remaining)->new SableContactRegions.Check(List.of(target(owner,1)),16));
        assertEquals(SableContactRegions.Status.TOO_BROAD,tooWide.status());
    }
}
