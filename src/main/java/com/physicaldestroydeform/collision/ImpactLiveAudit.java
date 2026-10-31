package com.physicaldestroydeform.collision;

import static com.physicaldestroydeform.collision.CollisionFrame.*;

/** Sable 2.0.6 callbacks report hit-space position, but not a signed approach
 * velocity or an independently validated solver normal. Gate proxy evidence
 * separately; never promote this point to an exact solver contact. */
public final class ImpactLiveAudit {
    private ImpactLiveAudit() {}

    public static ImpactMath.Body body(BlockTarget target, Body captured) {
        if(target==null || !target.valid())return null;
        if(target.space()==Space.STATIC_WORLD)return ImpactMath.Body.staticWorld();
        if(target.space()!=Space.SABLE_PLOT || captured==null || !captured.valid())return null;
        return captured.impactBody();
    }
    public static ImpactMath.Result evaluate(Evidence e, boolean bothOwnersVerified,
                                             ImpactMath.ProxyPolicy policy) {
        return evaluate(e,bothOwnersVerified,false,policy);
    }
    public static ImpactMath.Result evaluate(Evidence e, boolean bothOwnersVerified,
                                             boolean verifiedWorldFullCube,
                                             ImpactMath.ProxyPolicy policy) {
        if(e==null || !bothOwnersVerified || e.hit()==null || e.other()==null)
            return ImpactMath.Result.reject(ImpactMath.Reason.OWNER_UNVERIFIED);
        if(e.phase()!=Phase.PRE_SOLVER || e.epoch()<0)
            return ImpactMath.Result.reject(ImpactMath.Reason.STALE_SNAPSHOT);
        ImpactMath.Body a=body(e.hit(),e.hitBody());
        ImpactMath.Body b=body(e.other(),e.otherBody());
        if(a==null||b==null)return ImpactMath.Result.reject(ImpactMath.Reason.MASS_INVALID);
        // No exact live contact is available in this bridge. Avoid the six-face
        // and angular guard work for policy-disabled or unconfigured proxy runs.
        if(policy==null || !policy.enabled())
            return ImpactMath.Result.reject(ImpactMath.Reason.POLICY_DISABLED);
        if(!policy.configured())
            return ImpactMath.Result.reject(ImpactMath.Reason.POLICY_UNCONFIGURED);
        // For an audited STATIC_WORLD full cube only, Sable's original callback
        // point is in the world block frame (Rust hooks.rs: handle_block_params).
        // A unique visible face is independently checked; no reciprocal contact
        // geometry, fake normal from centers, or abs(impactVelocity) is accepted.
        var face=ImpactFaceEstimate.fromCallback(e,verifiedWorldFullCube);
        var normal=face.normalBtoA();
        boolean rotationDominated=normal!=null && rotationMayDominate(e,a,b,normal);
        var result=ImpactMath.select(
                new ImpactMath.ExactInput(a,b,null,null,true,true,false),
                new ImpactMath.ProxyInput(a,b,normal,true,true,face.verified(),rotationDominated),policy);
        return result.approved() && result.kind()==ImpactMath.Kind.CONSERVATIVE_PROXY
                ? new ImpactMath.Result(result.kind(),result.reason(),result.budget(),
                    result.closingSpeed(),result.effectiveMass(),
                    "BLOCK_FACE_ESTIMATE:VERIFIED_WORLD_FULL_CUBE_SINGLE_FACE") : result;
    }
    /** Conservative upper bound: angular speed * max distance to any hit voxel corner.
     * If rotation can explain the normal approach, translation-only proxy is denied. */
    private static boolean rotationMayDominate(Evidence e, ImpactMath.Body a,
                                                ImpactMath.Body b, FrameMath.Vec n) {
        double closing=Math.max(0,-a.linearWorld().minus(b.linearWorld()).dot(n));
        if(!Double.isFinite(closing))return true;
        if(closing<=0)
            return a.angularWorld().squareLength()>1e-24
                    || b.angularWorld().squareLength()>1e-24;
        return rotationCouldDominate(e.hit(),e.hitBody(),a,closing)
                || rotationCouldDominate(e.other(),e.otherBody(),b,closing);
    }
    private static boolean rotationCouldDominate(BlockTarget t, Body captured,
                                                   ImpactMath.Body b,double closing) {
        if(b.isStatic())return false;
        double w=Math.sqrt(b.angularWorld().squareLength());
        if(!Double.isFinite(w))return true;
        if(w<=1e-12)return false;
        if(t==null || captured==null || captured.pose()==null || !captured.pose().valid())return true;
        try {
            var pose=captured.pose();
            var center=pose.toWorldPoint(new FrameMath.Vec(t.x()+.5,t.y()+.5,t.z()+.5));
            var relative=center.minus(b.centerWorld());
            double scaleMax=Math.max(Math.abs(pose.scale().x()),
                    Math.max(Math.abs(pose.scale().y()),Math.abs(pose.scale().z())));
            double radius=Math.sqrt(relative.squareLength()) + 0.8660254037844386*scaleMax;
            return !Double.isFinite(radius) || w*radius>=closing;
        }catch(IllegalArgumentException ex){return true;}
    }
}
