package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static com.physicaldestroydeform.collision.CollisionFrame.*;
import static com.physicaldestroydeform.collision.FrameMath.*;
import static com.physicaldestroydeform.collision.ImpactMath.*;
import static org.junit.jupiter.api.Assertions.*;

class NativePlotDirectionTest {
    private static final String DIM="minecraft:overworld";
    private static final UUID A=UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID B=UUID.fromString("00000000-0000-0000-0000-000000000022");
    private static final ProxyPolicy POLICY=new ProxyPolicy(true,.5,100);
    private static final Pose IDENTITY=new Pose(new Vec(0,0,0),new Vec(0,0,0),
            new Vec(1,1,1),0,0,0,1);

    private static CollisionFrame.Body body(UUID id,int runtime,Vec velocity,Vec angular) {
        var physical=new ImpactMath.Body(false,10,.1,Tensor.zero(),new Vec(0,0,0),velocity,angular);
        return new CollisionFrame.Body(id,runtime*16,runtime*16+15,0,15,IDENTITY,velocity,angular,
                new Vec(0,0,0),true,physical,runtime);
    }
    private static Snapshot pre(long epoch,long tick,Vec velocityA,Vec velocityB,
                                 Vec angularA,Vec angularB) {
        return new Snapshot(epoch,tick,DIM,Phase.PRE_SOLVER,List.of(
                body(A,1,velocityA,angularA),body(B,2,velocityB,angularB)),true);
    }
    private static Evidence e(Snapshot s) {
        var a=s.bodies().get(0);var b=s.bodies().get(1);
        return new Evidence(s.epoch(),s.serverTick(),DIM,Phase.PRE_SOLVER,
                new BlockTarget(Space.SABLE_PLOT,DIM,A,16,2,2,true,"VERIFIED"),
                new BlockTarget(Space.SABLE_PLOT,DIM,B,32,3,3,true,"VERIFIED"),
                null,null,null,a,b,Quality.DIAGNOSTIC_ONLY,"CONTACT_UNVERIFIED");
    }
    // Normal A->B = +X, B->A = -X. A velocity +X -> approaching B.
    private static NativeCollisionTelemetry.Contact contact(long tick) {
        return new NativeCollisionTelemetry.Contact(1,2,10,1,0,0,-1,0,0,
                0,0,0,0,0,0,DIM,tick);
    }
    private static NativeCollisionTelemetry.Contact swapped(long tick) {
        return new NativeCollisionTelemetry.Contact(2,1,10,-1,0,0,1,0,0,
                0,0,0,0,0,0,DIM,tick);
    }
    @Test void approvesRealNativePairWithoutAnyExactContactCoordinate() {
        Snapshot s1=pre(11,100,new Vec(4,0,0),new Vec(0,0,0),new Vec(0,0,0),new Vec(0,0,0));
        Snapshot s2=pre(12,100,new Vec(3,0,0),new Vec(0,0,0),new Vec(0,0,0),new Vec(0,0,0));
        var direction=NativePlotDirection.prepare(List.of(contact(100),swapped(100)),List.of(s1,s2));
        assertEquals(1,direction.acceptedPairs());
        assertEquals(0,direction.rejectedPairs());
        var r=direction.evaluate(e(s1),POLICY);
        assertEquals(Kind.CONSERVATIVE_PROXY,r.kind());
        assertEquals(Reason.APPROVED,r.reason());
        assertEquals(11.25,r.budget(),1e-8); // min 0.5 * 0.5 * 10 * 3^2
        assertNull(e(s1).contactWorldHypothesis());
        assertEquals(-1.0,direction.approvedNormal(e(s1)).x(),1e-12);
        assertEquals(0.0,direction.approvedNormal(e(s1)).y(),1e-12);
        assertEquals(0.0,direction.approvedNormal(e(s1)).z(),1e-12);
    }
    @Test void excludesTangentialAndSeparationInAnyPreStep() {
        Snapshot approaching=pre(11,100,new Vec(4,0,0),new Vec(0,0,0),new Vec(0,0,0),new Vec(0,0,0));
        Snapshot separating=pre(12,100,new Vec(-4,0,0),new Vec(0,0,0),new Vec(0,0,0),new Vec(0,0,0));
        var d=NativePlotDirection.prepare(List.of(contact(100)),List.of(approaching,separating));
        assertEquals(Reason.SEPARATING_OR_TANGENTIAL,d.evaluate(e(approaching),POLICY).reason());
    }
    @Test void rotationDominatedDoesNotMintTranslationDamage() {
        var rotating=pre(11,100,new Vec(1,0,0),new Vec(0,0,0),
                new Vec(0,2,0),new Vec(0,0,0));
        var d=NativePlotDirection.prepare(List.of(contact(100)),List.of(rotating));
        assertEquals(Reason.ROTATION_UNRESOLVED,d.evaluate(e(rotating),POLICY).reason());
    }
    @Test void nonOpposingNormalsOrConflictingContactFacesReject() {
        var s=pre(11,100,new Vec(3,0,0),new Vec(0,0,0),new Vec(0,0,0),new Vec(0,0,0));
        var bad=new NativeCollisionTelemetry.Contact(1,2,10,1,0,0,1,0,0,
                0,0,0,0,0,0,DIM,100);
        var d=NativePlotDirection.prepare(List.of(bad),List.of(s));
        assertEquals(1,d.rejectedPairs());
        assertEquals(Reason.DIRECTION_UNVERIFIED,d.evaluate(e(s),POLICY).reason());
        var opposite=new NativeCollisionTelemetry.Contact(1,2,10,-1,0,0,1,0,0,
                0,0,0,0,0,0,DIM,100);
        d=NativePlotDirection.prepare(List.of(contact(100),opposite),List.of(s));
        assertEquals(1,d.rejectedPairs());
    }
    @Test void missingTickRuntimeOrSubstepFailsClosed() {
        var s=pre(11,100,new Vec(3,0,0),new Vec(0,0,0),new Vec(0,0,0),new Vec(0,0,0));
        assertEquals(Reason.DIRECTION_UNVERIFIED,
                NativePlotDirection.prepare(List.of(contact(99)),List.of(s)).evaluate(e(s),POLICY).reason());
        assertEquals(Reason.DIRECTION_UNVERIFIED,
                NativePlotDirection.prepare(List.of(),List.of(s)).evaluate(e(s),POLICY).reason());
        var s2=pre(12,100,new Vec(3,0,0),new Vec(0,0,0),new Vec(0,0,0),new Vec(0,0,0));
        var d=NativePlotDirection.prepare(List.of(contact(100)),List.of(s2));
        assertEquals(Reason.STALE_SNAPSHOT,d.evaluate(e(s),POLICY).reason());
    }
    @Test void respectsDisabledAndUnconfiguredPolicyWithoutBypass() {
        var s=pre(11,100,new Vec(3,0,0),new Vec(0,0,0),new Vec(0,0,0),new Vec(0,0,0));
        var d=NativePlotDirection.prepare(List.of(contact(100)),List.of(s));
        assertEquals(Reason.POLICY_DISABLED,d.evaluate(e(s),ProxyPolicy.disabled()).reason());
        assertEquals(Reason.POLICY_UNCONFIGURED,d.evaluate(e(s),new ProxyPolicy(true,Double.NaN,100)).reason());
    }
    @Test void nativeDirectionCannotCreateExactContactEnergy() {
        var s=pre(11,100,new Vec(3,0,0),new Vec(0,0,0),new Vec(0,0,0),new Vec(0,0,0));
        var d=NativePlotDirection.prepare(List.of(contact(100)),List.of(s));
        var r=d.evaluate(e(s),POLICY);
        assertEquals(Kind.CONSERVATIVE_PROXY,r.kind());
        assertTrue(r.source().contains("NATIVE_PLOT_PAIR_"));
    }
}
