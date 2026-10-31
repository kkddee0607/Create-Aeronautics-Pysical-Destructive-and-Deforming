package com.physicaldestroydeform.collision;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.physicaldestroydeform.collision.CollisionFrame.*;
import static com.physicaldestroydeform.collision.FrameMath.*;

class ImpactFaceEstimateTest {
    private Evidence sample(BlockTarget hit,Vec point) {
        return new Evidence(1,20,"minecraft:overworld",Phase.PRE_SOLVER,hit,null,point,null,null,
                null,null,Quality.DIAGNOSTIC_ONLY,"UNVERIFIED_CONTACT_POINT");
    }
    @Test void topFaceOfVerifiedStaticCubeGivesBtoADown() {
        BlockTarget a=new BlockTarget(Space.STATIC_WORLD,"minecraft:overworld",null,40,63,-2,true,"TEST");
        var estimate=ImpactFaceEstimate.fromCallback(sample(a,new Vec(40.5,64.001,-1.5)),true);
        assertTrue(estimate.verified());
        assertEquals(new Vec(0,-1,0),estimate.normalBtoA());
    }
    @Test void facesAreOrientedAgainstHitCubeOutward() {
        BlockTarget a=new BlockTarget(Space.STATIC_WORLD,"minecraft:overworld",null,5,8,10,true,"TEST");
        assertEquals(new Vec(1,0,0),ImpactFaceEstimate.fromCallback(sample(a,new Vec(5.001,8.5,10.5)),true).normalBtoA());
        assertEquals(new Vec(-1,0,0),ImpactFaceEstimate.fromCallback(sample(a,new Vec(5.999,8.5,10.5)),true).normalBtoA());
    }
    @Test void cornerAmbiguousAndDisplacedPointReject() {
        BlockTarget a=new BlockTarget(Space.STATIC_WORLD,"minecraft:overworld",null,5,8,10,true,"TEST");
        assertEquals(ImpactFaceEstimate.Reason.FACE_NOT_UNIQUE,
                ImpactFaceEstimate.fromCallback(sample(a,new Vec(6,9,10.5)),true).reason());
        assertEquals(ImpactFaceEstimate.Reason.CONTACT_OUTSIDE_CUBE,
                ImpactFaceEstimate.fromCallback(sample(a,new Vec(5.5,8.5,30)),true).reason());
    }
    @Test void unverifiedCubeOrPlotTargetNeverProducesDirection() {
        BlockTarget w=new BlockTarget(Space.STATIC_WORLD,"minecraft:overworld",null,5,8,10,true,"TEST");
        BlockTarget p=new BlockTarget(Space.SABLE_PLOT,"minecraft:overworld",java.util.UUID.randomUUID(),5,8,10,true,"TEST");
        assertFalse(ImpactFaceEstimate.fromCallback(sample(w,new Vec(5.5,9,10.5)),false).verified());
        assertFalse(ImpactFaceEstimate.fromCallback(sample(p,new Vec(5.5,9,10.5)),true).verified());
    }
}
