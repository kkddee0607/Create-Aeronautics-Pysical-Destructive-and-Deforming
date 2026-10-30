package com.physicaldestroydeform.collision;

import dev.ryanhcode.sable.api.physics.mass.MassData;
import org.joml.Matrix3dc;

import static com.physicaldestroydeform.collision.FrameMath.*;

/** Copies Sable 2.0.6 MassData into immutable data on the pre-solver physics thread.
 * Never retain a live mass tracker or JOML matrix for later server-tick use. */
public final class SableMassAdapter {
    private SableMassAdapter() {}

    public static ImpactMath.Body snapshot(MassData data, Pose pose, Vec linear, Vec angular) {
        if(data==null||pose==null||!pose.valid()||linear==null||angular==null
                ||!linear.finite()||!angular.finite())return null;
        try {
            // An arbitrary scale changes the inertia tensor's units. Do not pretend
            // a rotated unscaled tensor is exact for scaled sublevels.
            if(Math.abs(pose.scale().x()-1)>1e-6 || Math.abs(pose.scale().y()-1)>1e-6
                    ||Math.abs(pose.scale().z()-1)>1e-6)return null;
            double mass=data.getMass();
            double inverseMass=data.getInverseMass();
            Matrix3dc m=data.getInverseInertiaTensor();
            var center=data.getCenterOfMass();
            if(m==null||center==null)return null;
            // JOML m10 is row 0/column 1 in its column-major convention.
            ImpactMath.Tensor local=new ImpactMath.Tensor(
                    m.m00(),m.m10(),m.m20(),
                    m.m01(),m.m11(),m.m21(),
                    m.m02(),m.m12(),m.m22());
            ImpactMath.Tensor world=local.rotate(
                    pose.toWorldVector(new Vec(1,0,0)),
                    pose.toWorldVector(new Vec(0,1,0)),
                    pose.toWorldVector(new Vec(0,0,1)));
            var result=new ImpactMath.Body(false,mass,inverseMass,world,
                    pose.toWorldPoint(new Vec(center.x(),center.y(),center.z())),linear,angular);
            return result.valid()?result:null;
        } catch(RuntimeException invalid) {
            return null; // Expose availability separately; never fabricate mass.
        }
    }
}
