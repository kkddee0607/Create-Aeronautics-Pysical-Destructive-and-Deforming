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
        return evaluate(e,bothOwnersVerified,verifiedWorldFullCube,policy,null);
    }
    public static ImpactMath.Result evaluate(Evidence e, boolean bothOwnersVerified,
                                             boolean verifiedWorldFullCube,
                                             ImpactMath.ProxyPolicy policy,
                                             NativePlotDirection.Prepared plotDirections) {
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
        // Plot-Plot: independent native collider normals and body runtime IDs.
        // Because the effect feed is post-solver and substep-aggregated, every
        // pre-solver snapshot in that tick must independently pass approach and
        // rotation checks. This is never labelled EXACT_CONTACT.
        if(e.hit().plot() && e.other().plot())return plotDirections==null
                ? ImpactMath.Result.reject(ImpactMath.Reason.DIRECTION_UNVERIFIED)
                : plotDirections.evaluate(e,policy);
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

    /** Evidence-only replay of the existing conservative rotation guard.
     * The voxel center is NOT a solver contact point. Its omega cross r normal
     * projection is an illustrative diagnostic, NEVER an approval input. */
    public record RotationSide(String status,double omegaMagnitude,double centerDistance,
                               double voxelCornerRadiusUpper,double tangentialSpeedUpper,
                               double blockCenterOmegaCrossRNormal,boolean guardRejects) {
        public String explain() {
            return "status="+status+",omegaMagnitude="+omegaMagnitude
                    +",centerDistance="+centerDistance+",cornerRadiusUpper="+voxelCornerRadiusUpper
                    +",omegaRadiusUpper="+tangentialSpeedUpper
                    +",blockCenterOmegaCrossRNormal_DIAGNOSTIC_ONLY="+blockCenterOmegaCrossRNormal
                    +",guardRejects="+guardRejects;
        }
    }
    public record RotationEvidence(double signedTranslationNormalVelocity,double translationClosing,
                                   RotationSide hit,RotationSide other,boolean rotationGateRejects) {
        public String explain() {
            return "signedTranslationNormalVelocity="+signedTranslationNormalVelocity
                    +",translationClosing="+translationClosing+",hit={"+hit.explain()
                    +"},other={"+other.explain()+"},rotationGateRejects="+rotationGateRejects
                    +",rule=ANY_SIDE_OMEGA_RADIUS_GE_CLOSING"
                    +",contactPoint=UNAVAILABLE candidatePoint=BLOCK_VOXEL_CENTER_DIAGNOSTIC_ONLY";
        }
    }
    public static RotationEvidence rotationEvidence(Evidence e,FrameMath.Vec normalBtoA) {
        if(e==null||normalBtoA==null||!normalBtoA.finite())
            throw new IllegalArgumentException("rotation evidence requires a finite normal");
        ImpactMath.Body a=body(e.hit(),e.hitBody());
        ImpactMath.Body b=body(e.other(),e.otherBody());
        if(a==null||b==null)throw new IllegalArgumentException("missing motion body");
        double signed=a.linearWorld().minus(b.linearWorld()).dot(normalBtoA);
        double closing=Math.max(0,-signed);
        return new RotationEvidence(signed,closing,
                rotationSide(e.hit(),e.hitBody(),a,normalBtoA,closing),
                rotationSide(e.other(),e.otherBody(),b,normalBtoA,closing),
                rotationMayDominate(e,a,b,normalBtoA));
    }
    private static RotationSide rotationSide(BlockTarget t,Body captured,ImpactMath.Body b,
                                             FrameMath.Vec normal,double closing) {
        if(b.isStatic())return new RotationSide("STATIC_WORLD",0,0,0,0,0,false);
        double omega=Math.sqrt(b.angularWorld().squareLength());
        if(!Double.isFinite(omega))return new RotationSide("INVALID_OMEGA",omega,
                Double.NaN,Double.NaN,Double.NaN,Double.NaN,true);
        // Mirror the original guard, including its low-omega thresholds.
        if(closing<=0)return new RotationSide("NO_TRANSLATIONAL_APPROACH",omega,
                Double.NaN,Double.NaN,Double.NaN,Double.NaN,b.angularWorld().squareLength()>1e-24);
        if(omega<=1e-12)return new RotationSide("OMEGA_BELOW_GUARD_THRESHOLD",omega,
                Double.NaN,Double.NaN,0,0,false);
        if(t==null||captured==null||captured.pose()==null||!captured.pose().valid())
            return new RotationSide("POSE_UNAVAILABLE",omega,Double.NaN,Double.NaN,
                    Double.NaN,Double.NaN,true);
        try {
            var pose=captured.pose();
            var center=pose.toWorldPoint(new FrameMath.Vec(t.x()+.5,t.y()+.5,t.z()+.5));
            var relative=center.minus(b.centerWorld());
            double distance=Math.sqrt(relative.squareLength());
            double scaleMax=Math.max(Math.abs(pose.scale().x()),
                    Math.max(Math.abs(pose.scale().y()),Math.abs(pose.scale().z())));
            double radius=distance+0.8660254037844386*scaleMax;
            double upper=omega*radius;
            double projected=b.angularWorld().cross(relative).dot(normal);
            return new RotationSide(!Double.isFinite(radius)?"INVALID_RADIUS":"VALID_VOXEL_UPPER_BOUND",
                    omega,distance,radius,upper,projected,!Double.isFinite(radius)||upper>=closing);
        }catch(IllegalArgumentException ex) {
            return new RotationSide("INVALID_POSE_TRANSFORM",omega,Double.NaN,Double.NaN,
                    Double.NaN,Double.NaN,true);
        }
    }

    /** Conservative upper bound: angular speed * max distance to any hit voxel corner.
     * If rotation can explain the normal approach, translation-only proxy is denied. */
    static boolean rotationMayDominate(Evidence e, ImpactMath.Body a,
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
