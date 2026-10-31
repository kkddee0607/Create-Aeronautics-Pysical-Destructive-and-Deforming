package com.physicaldestroydeform.collision;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static com.physicaldestroydeform.collision.CollisionFrame.*;
import static com.physicaldestroydeform.collision.FrameMath.*;
import static com.physicaldestroydeform.collision.ImpactMath.*;

class ImpactLiveAuditTest {
    private final UUID id=UUID.fromString("12345678-0000-0000-0000-000000000001");
    private final CollisionFrame.Body body;
    private final BlockTarget plot,world;
    ImpactLiveAuditTest() {
        Pose pose=new Pose(new Vec(10,20,30),new Vec(0,0,0),new Vec(1,1,1),0,0,0,1);
        var mass=new ImpactMath.Body(false,2,.5,Tensor.zero(),
                new Vec(10,20,30),new Vec(-3,0,0),new Vec(0,0,0));
        body=new CollisionFrame.Body(id,0,15,0,15,pose,new Vec(-3,0,0),new Vec(0,0,0),
                new Vec(0,0,0),true,mass);
        plot=new BlockTarget(Space.SABLE_PLOT,"overworld",id,2,3,2,true,"TEST");
        world=new BlockTarget(Space.STATIC_WORLD,"overworld",null,30,3,30,true,"TEST");
    }
    private Evidence observation(Vec raw) {
        return new Evidence(10,100,"overworld",Phase.PRE_SOLVER,plot,world,raw,
                new Vec(99999999,0,0),null,body,null,Quality.DIAGNOSTIC_ONLY,"FRAME_UNRESOLVED");
    }
    @Test void ownerStillVerifiedButBadGeometryCannotApproveImpact() {
        var e=observation(new Vec(0,0,0));
        Result r=ImpactLiveAudit.evaluate(e,true,ProxyPolicy.disabled());
        assertEquals(Kind.UNRESOLVED,r.kind());assertEquals(0,r.budget());
        assertEquals(Reason.POLICY_DISABLED,r.reason());
        r=ImpactLiveAudit.evaluate(e,true,new ProxyPolicy(true,.5,100));
        assertEquals(Reason.DIRECTION_UNVERIFIED,r.reason());
        assertEquals(0,r.budget());
    }
    private Evidence worldContact(double plotVelocityY,double plotAngularZ,Vec point) {
        var impact = new ImpactMath.Body(false,2,.5,Tensor.zero(),
                new Vec(31,6,31),new Vec(0,plotVelocityY,0),new Vec(0,0,plotAngularZ));
        var moving = new CollisionFrame.Body(id,0,15,0,15,body.pose(),
                impact.linearWorld(),impact.angularWorld(),new Vec(0,0,0),true,impact);
        return new Evidence(10,100,"overworld",Phase.PRE_SOLVER,world,plot,point,
                null,null,null,moving,Quality.DIAGNOSTIC_ONLY,"CONTACT_FRAME_UNVERIFIED");
    }
    @Test void approvedProxyWorksWithoutPreciseContactAndWithVerifiedCubeFace() {
        var e=worldContact(-3,0,new Vec(30.5,4,30.5));
        Result r=ImpactLiveAudit.evaluate(e,true,true,new ProxyPolicy(true,.5,20));
        assertEquals(Kind.CONSERVATIVE_PROXY,r.kind());
        assertEquals(Reason.APPROVED,r.reason());
        assertEquals(4.5,r.budget(),1e-9);
        assertTrue(r.source().startsWith("BLOCK_FACE_ESTIMATE"));
        assertNull(e.confirmedWorldContact());
    }
    @Test void noFaceEvidenceSeparatingAndRotationDominatedAllReject() {
        var policy=new ProxyPolicy(true,.5,20);
        var impact=worldContact(-3,0,new Vec(30.5,4,30.5));
        assertEquals(Reason.DIRECTION_UNVERIFIED,
                ImpactLiveAudit.evaluate(impact,true,false,policy).reason());
        assertEquals(Reason.SEPARATING_OR_TANGENTIAL,
                ImpactLiveAudit.evaluate(worldContact(3,0,new Vec(30.5,4,30.5)),true,true,policy).reason());
        assertEquals(Reason.ROTATION_UNRESOLVED,
                ImpactLiveAudit.evaluate(worldContact(-3,100,new Vec(30.5,4,30.5)),true,true,policy).reason());
        assertEquals(Reason.DIRECTION_UNVERIFIED,
                ImpactLiveAudit.evaluate(worldContact(-3,0,new Vec(31,4,30.5)),true,true,policy).reason());
    }
    @Test void cannotPromoteMissingTargetOrPreSolver() {
        var e=observation(new Vec(0,0,0));
        assertEquals(Reason.OWNER_UNVERIFIED,ImpactLiveAudit.evaluate(e,false,ProxyPolicy.disabled()).reason());
        var after=new Evidence(e.epoch(),e.serverTick(),e.dimension(),Phase.POST_SOLVER,
                e.hit(),e.other(),e.contactRaw(),null,null,e.hitBody(),e.otherBody(),Quality.INVALID,"POST");
        assertEquals(Reason.STALE_SNAPSHOT,ImpactLiveAudit.evaluate(after,true,ProxyPolicy.disabled()).reason());
    }
    @Test void noMassTrackerMeansRejectEvenIfOwnerValid() {
        var old=new CollisionFrame.Body(id,0,15,0,15,body.pose(),body.linearRaw(),body.angularRaw(),
                body.centerRaw(),true);
        var e=new Evidence(10,100,"overworld",Phase.PRE_SOLVER,plot,world,null,null,null,old,null,
                Quality.DIAGNOSTIC_ONLY,"NONE");
        assertEquals(Reason.MASS_INVALID,ImpactLiveAudit.evaluate(e,true,ProxyPolicy.disabled()).reason());
    }
    @Test void moreRogExplainsExactExistingRotationGuardWithoutChangingDecision() {
        var e=worldContact(-3,1,new Vec(30.5,4,30.5));
        // Verified world top-face normal B -> A. Voxel-center projection is diagnostic only.
        var normal=new Vec(0,-1,0);
        var report=ImpactLiveAudit.rotationEvidence(e,normal);
        var moving=ImpactLiveAudit.body(e.other(),e.otherBody());
        var stationary=ImpactLiveAudit.body(e.hit(),e.hitBody());
        assertEquals(3.0,report.translationClosing(),1e-10);
        assertEquals(0.0,report.hit().omegaMagnitude(),1e-10);
        assertTrue(report.other().omegaMagnitude()>0);
        assertTrue(report.other().voxelCornerRadiusUpper()>=report.other().centerDistance());
        assertEquals(ImpactLiveAudit.rotationMayDominate(e,stationary,moving,normal),
                report.rotationGateRejects());
        assertEquals(ImpactMath.Reason.ROTATION_UNRESOLVED,
                ImpactLiveAudit.evaluate(e,true,true,new ProxyPolicy(true,.5,100)).reason());
        assertTrue(report.explain().contains("BLOCK_VOXEL_CENTER_DIAGNOSTIC_ONLY"));
    }
    @Test void moreRogDoesNotPromoteRotationAtRestIntoApprovedContact() {
        var e=worldContact(0,0,new Vec(30.5,4,30.5));
        var report=ImpactLiveAudit.rotationEvidence(e,new Vec(0,-1,0));
        assertEquals(0.0,report.translationClosing(),1e-10);
        assertFalse(report.rotationGateRejects());
        assertEquals(ImpactMath.Reason.SEPARATING_OR_TANGENTIAL,
                ImpactLiveAudit.evaluate(e,true,true,new ProxyPolicy(true,.5,100)).reason());
    }

}
