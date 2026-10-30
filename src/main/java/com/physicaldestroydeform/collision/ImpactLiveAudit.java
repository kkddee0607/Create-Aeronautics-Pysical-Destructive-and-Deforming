package com.physicaldestroydeform.collision;

import static com.physicaldestroydeform.collision.CollisionFrame.*;

/** The callback ABI does NOT establish normal, world point, or pre-solver scalar semantics.
 * Reject unproven geometry/approach. Tested math can be enabled when evidence exists. */
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
        if(e==null || !bothOwnersVerified || e.hit()==null || e.other()==null)
            return ImpactMath.Result.reject(ImpactMath.Reason.OWNER_UNVERIFIED);
        if(e.phase()!=Phase.PRE_SOLVER || e.epoch()<0)
            return ImpactMath.Result.reject(ImpactMath.Reason.STALE_SNAPSHOT);
        ImpactMath.Body a=body(e.hit(),e.hitBody());
        ImpactMath.Body b=body(e.other(),e.otherBody());
        if(a==null||b==null)return ImpactMath.Result.reject(ImpactMath.Reason.MASS_INVALID);
        // The existing Sable callback's raw impactPosition may alternate between
        // world-like and plot-like frames. Neither is a VALIDATED contact point.
        // No direction is inferred from block centers or abs(signedVelocity).
        return ImpactMath.select(
                new ImpactMath.ExactInput(a,b,null,null,true,true,false),
                new ImpactMath.ProxyInput(a,b,null,true,true,false,false),policy);
    }
}
