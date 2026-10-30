package com.physicaldestroydeform.collision;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.physicaldestroydeform.collision.FrameMath.*;

class FrameMathTest {
    private static Pose yaw90() {
        double h=Math.sqrt(0.5);
        return new Pose(new Vec(100,20,30),new Vec(4,0,0),new Vec(1,1,1),0,h,0,h);
    }
    @Test void utFrame01TranslatesAndRotatesPositionWithoutTranslatingVectors() {
        Pose pose=yaw90();
        Vec world=pose.toWorldPoint(new Vec(6,0,0));
        assertEquals(100,world.x(),1e-9);assertEquals(20,world.y(),1e-9);
        assertEquals(28,world.z(),1e-9);
        assertEquals(0,pose.toWorldVector(new Vec(2,0,0)).x(),1e-9);
        assertEquals(-2,pose.toWorldVector(new Vec(2,0,0)).z(),1e-9);
        assertEquals(6,pose.toLocalPoint(world).x(),1e-9);
        assertEquals(-1,pose.toWorldNormal(new Vec(1,0,0)).z(),1e-9);
    }
    @Test void utFrame01NormalIsInverseTransposedWhenScaleIsNonUniform() {
        Pose pose=new Pose(new Vec(0,0,0),new Vec(0,0,0),new Vec(2,1,1),0,0,0,1);
        Vec normal=pose.toWorldNormal(new Vec(1,1,0));
        assertEquals(1/Math.sqrt(5),normal.x(),1e-9);
        assertEquals(2/Math.sqrt(5),normal.y(),1e-9);
        assertEquals(1/Math.sqrt(2),pose.toLocalNormal(new Vec(1,2,0)).x(),1e-9);
    }
    @Test void utFrame02NonfiniteNormalAndZeroDirectionAreRejected() {
        assertThrows(IllegalArgumentException.class,()->yaw90().toWorldNormal(new Vec(Double.NaN,0,1)));
        assertThrows(IllegalArgumentException.class,()->yaw90().toWorldNormal(new Vec(0,0,0)));
        assertThrows(IllegalArgumentException.class,()->yaw90().toWorldPoint(new Vec(Double.POSITIVE_INFINITY,0,0)));
    }
    @Test void contactVelocityIncludesAngularComponentButNoCallbackScalar() {
        Vec result=FrameMath.contactVelocity(new Vec(1,0,0),new Vec(0,0,2),
                new Vec(0,3,0),new Vec(0,0,0));
        assertEquals(-5,result.x(),1e-9);
        assertThrows(IllegalArgumentException.class,()->FrameMath.contactVelocity(new Vec(0,0,0),
                new Vec(Double.NaN,0,0),new Vec(1,1,1),new Vec(0,0,0)));
    }
}
