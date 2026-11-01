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
    @Test void moreThanFourSideTargetsFailClosed() {
        var points=new ArrayList<NativeCollisionTelemetry.Contact>();
        for(int i=0;i<5;i++)points.add(contact(i));
        var result=SableContactRegions.collect(PAIR,points,
                (e,owner,remaining)->new SableContactRegions.Check(List.of(target(owner,(int)e.contactAX())),1));
        assertEquals(SableContactRegions.Status.TOO_BROAD,result.status());
        assertEquals(0,result.regions().size());
    }
    @Test void nonMatchingContactFrameRejected() {
        var bad=new NativeCollisionTelemetry.Contact(7,-1,42,0,0,0,0,0,0,
                0,0,0,10,0,0,"minecraft:overworld",9);
        var result=SableContactRegions.collect(PAIR,List.of(bad),
                (e,owner,remaining)->new SableContactRegions.Check(List.of(target(owner,1)),1));
        assertEquals(SableContactRegions.Status.NO_VERIFIED_REGION,result.status());
        assertEquals(1,result.rejected());
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
