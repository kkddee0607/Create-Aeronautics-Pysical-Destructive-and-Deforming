package com.physicaldestroydeform.collision;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;

import static com.physicaldestroydeform.collision.CollisionFrame.*;
import static com.physicaldestroydeform.collision.FrameMath.*;
import static org.junit.jupiter.api.Assertions.*;

class ContactPairMatcherTest {
    static final int PLOT_X = 20481033, PLOT_Z = 20483080;
    private static Body plot(UUID id, int x, int z, Pose pose) {
        return new Body(id, x-16, x+16, z-16, z+16, pose,
                new Vec(0,0,0),new Vec(0,0,0),new Vec(x,127,z), true);
    }

    @Test void liveWorldPlotEvidenceIsPromotedOnlyAfterIndependentPair() {
        UUID id=UUID.randomUUID();
        Pose pose=new Pose(new Vec(72,63,-46), new Vec(PLOT_X,127,PLOT_Z),
                new Vec(1,1,1), 0,0,0,1);
        Snapshot s=new Snapshot(601,28655,"overworld",Phase.PRE_SOLVER,
                List.of(plot(id,PLOT_X,PLOT_Z,pose)),true);
        Evidence world=s.observe(72,62,-47,true,PLOT_X,127,PLOT_Z,
                72.914306640625,62.99997329711914,-46.08872985839844,0.275810003);
        Evidence local=s.observe(PLOT_X,127,PLOT_Z,true,72,62,-47,
                PLOT_X+0.914306640625,126.99997329711914,PLOT_Z-0.08872985839844,0.275810003);
        assertEquals(Quality.DIAGNOSTIC_ONLY,world.quality());
        assertNull(world.confirmedWorldContact());
        var result=ContactPairMatcher.match(List.of(world,local));
        assertEquals(1,result.matches().size());
        assertEquals(0,result.unpaired());
        assertEquals(0,result.invalid());
        assertTrue(result.matches().get(0).separation()<1e-4);
        Evidence checked=result.matches().get(0).firstEvidence();
        assertEquals(Quality.DIAGNOSTIC_ONLY,checked.quality());
        assertNull(checked.confirmedWorldContact());
        assertFalse(checked.canMutateWorld());
    }

    @Test void realPlotPlotPlaceholderCoordinatesAreRejectedNotCountedAsMatching() {
        UUID a=UUID.randomUUID(), b=UUID.randomUUID();
        Pose pa=new Pose(new Vec(79,63,-51),new Vec(20481033,128,20481032),
                new Vec(1,1,1),0,0,0,1);
        Pose pb=new Pose(new Vec(79,63,-51),new Vec(PLOT_X,129,PLOT_Z),
                new Vec(1,1,1),0,0,0,1);
        Snapshot s=new Snapshot(4826,29359,"overworld",Phase.PRE_SOLVER,
                List.of(plot(a,20481033,20481032,pa),plot(b,PLOT_X,PLOT_Z,pb)),true);
        Evidence first=s.observe(20481033,128,20481032,true,PLOT_X,129,PLOT_Z,
                74,63,-49,0);
        Evidence second=s.observe(PLOT_X,129,PLOT_Z,true,20481033,128,20481032,
                20481033.138340294,128.812499,20481032.361827508,0);
        assertEquals(Quality.DIAGNOSTIC_ONLY,first.quality());
        assertEquals("FRAME_UNRESOLVED_CONTACT_OUTSIDE_OWNER",first.reason());
        var result=ContactPairMatcher.match(List.of(first,second));
        assertEquals(1,result.matches().size()); // Target pairing is independent of geometry.
        assertTrue(result.matches().getFirst().separation()>100);
        assertEquals(0,result.invalid());
        assertNull(first.confirmedWorldContact());
    }

    @Test void rotatedIndependentPlotsMatchEvenWhenCallbacksAreInterleaved() {
        UUID a=UUID.randomUUID(), b=UUID.randomUUID();
        double h=Math.sqrt(.5);
        Pose pa=new Pose(new Vec(50,70,50),new Vec(5,128,5),
                new Vec(1,1,1),0,h,0,h);
        Pose pb=new Pose(new Vec(50,70,50),new Vec(40,128,40),
                new Vec(1,1,1),0,0,0,1);
        Snapshot s=new Snapshot(6,40,"overworld",Phase.PRE_SOLVER,List.of(
                plot(a,5,5,pa),plot(b,40,40,pb)),true);
        Vec point=new Vec(50.2,70.1,49.8);
        Vec fromA=pa.toLocalPoint(point),fromB=pb.toLocalPoint(point);
        Evidence aContact=s.observe(5,128,5,true,40,128,40,
                fromA.x(),fromA.y(),fromA.z(),1);
        Evidence bContact=s.observe(40,128,40,true,5,128,5,
                fromB.x(),fromB.y(),fromB.z(),1);
        Evidence noise=s.observe(99,70,99,false,0,0,0,99.5,70.5,99.5,1);
        var result=ContactPairMatcher.match(List.of(aContact,noise,bContact));
        assertEquals(1,result.matches().size());
        assertEquals(0,result.unpaired());
        assertEquals(1,result.invalid()); // Missing other-side target cannot form a pair.
        assertEquals(0,result.matches().get(0).separation(),1e-9);
        assertEquals(2,result.matches().get(0).second());
    }

    @Test void opposingCallbacksFarApartMustNotValidateEachOther() {
        UUID id=UUID.randomUUID();
        Pose pose=new Pose(new Vec(50,70,50),new Vec(5,128,5),new Vec(1,1,1),0,0,0,1);
        Snapshot s=new Snapshot(3,5,"overworld",Phase.PRE_SOLVER,List.of(plot(id,5,5,pose)),true);
        Evidence a=s.observe(5,128,5,true,51,70,50,6,128,5,1);
        Evidence b=s.observe(51,70,50,true,5,128,5,51.8,70,50,1);
        assertEquals(Quality.DIAGNOSTIC_ONLY,a.quality());
        assertEquals(Quality.DIAGNOSTIC_ONLY,b.quality());
        var result=ContactPairMatcher.match(List.of(a,b));
        assertEquals(1,result.matches().size());
        assertTrue(result.matches().getFirst().separation() > 0.25);
        assertEquals(0,result.unpaired());
    }

    @Test void multipleContactsWithSameOwnerPairAreExplicitlyAmbiguous() {
        UUID id=UUID.randomUUID();
        Pose pose=new Pose(new Vec(50,70,50),new Vec(5,128,5),new Vec(1,1,1),0,0,0,1);
        Snapshot s=new Snapshot(1,1,"overworld",Phase.PRE_SOLVER,List.of(plot(id,5,5,pose)),true);
        Evidence a=s.observe(5,128,5,true,50,70,50,5,128,5,1);
        Evidence b=s.observe(5,128,5,true,50,70,50,5.5,128,5,1);
        Evidence c=s.observe(50,70,50,true,5,128,5,50,70,50,1);
        var result=ContactPairMatcher.match(List.of(a,b,c));
        assertEquals(0,result.matches().size());
        assertEquals(3,result.ambiguous());
        assertEquals(0,result.unpaired());
    }

    @Test void malformedPostSolverAndWrongEpochNeverProduceVerifiedPairs() {
        UUID id=UUID.randomUUID();
        Pose pose=new Pose(new Vec(50,70,50),new Vec(5,128,5),new Vec(1,1,1),0,0,0,1);
        Body body=plot(id,5,5,pose);
        Snapshot pre=new Snapshot(9,55,"overworld",Phase.PRE_SOLVER,List.of(body),true);
        Evidence a=pre.observe(5,128,5,true,50,70,50,5,128,5,1);
        Evidence b=new Snapshot(10,55,"overworld",Phase.PRE_SOLVER,List.of(body),true)
                .observe(50,70,50,true,5,128,5,50,70,50,1);
        Evidence post=new Snapshot(9,55,"overworld",Phase.POST_SOLVER,List.of(body),true)
                .observe(50,70,50,true,5,128,5,50,70,50,1);
        assertEquals(Quality.INVALID,post.quality());
        assertEquals(0,ContactPairMatcher.match(List.of(a,b,post)).matches().size());
    }
}
