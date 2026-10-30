package com.physicaldestroydeform.collision;

import dev.ryanhcode.sable.api.physics.mass.MassData;
import org.joml.Matrix3d;
import org.joml.Matrix3dc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.jupiter.api.Test;

import static com.physicaldestroydeform.collision.FrameMath.*;
import static org.junit.jupiter.api.Assertions.*;

/** Sable 2.0.6 declared MassData ABI, not a hand-designed surrogate interface. */
class SableMassAdapterTest {
    private static final Vec ZERO=new Vec(0,0,0);
    private static MassData mass(double value, Vector3dc center) {
        return new MassData() {
            final Matrix3dc inertia=new Matrix3d().identity();
            @Override public double getMass(){return value;}
            @Override public double getInverseMass(){return 1/value;}
            @Override public Matrix3dc getInertiaTensor(){return inertia;}
            @Override public Matrix3dc getInverseInertiaTensor(){return inertia;}
            @Override public Vector3dc getCenterOfMass(){return center;}
        };
    }
    @Test void copiesLocalMassIntoRotatedWorldSnapshot() {
        double h=Math.sqrt(0.5);
        Pose rotated=new Pose(new Vec(10,0,0),ZERO,new Vec(1,1,1),0,0,h,h);
        var snap=SableMassAdapter.snapshot(mass(2,new Vector3d(5,0,0)),rotated,new Vec(-2,0,0),ZERO);
        assertNotNull(snap);
        assertEquals(10,snap.centerWorld().x(),1e-8);
        assertEquals(5,snap.centerWorld().y(),1e-8);
        assertEquals(.5,snap.inverseMass(),1e-8);
        assertEquals(1.5,snap.inverseNormalMass(new Vec(10,6,0),new Vec(1,0,0)),1e-8);
    }
    @Test void scaledPoseCannotPretendToUseUnscaledInertia() {
        Pose scaled=new Pose(ZERO,ZERO,new Vec(2,1,1),0,0,0,1);
        assertNull(SableMassAdapter.snapshot(mass(2,new Vector3d()),scaled,ZERO,ZERO));
    }
    @Test void missingAndInvalidMassAreRejected() {
        Pose identity=new Pose(ZERO,ZERO,new Vec(1,1,1),0,0,0,1);
        assertNull(SableMassAdapter.snapshot(mass(0,new Vector3d()),identity,ZERO,ZERO));
        assertNull(SableMassAdapter.snapshot(null,identity,ZERO,ZERO));
    }
}
