package com.physicaldestroydeform.collision;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Target resolution policy for PDD IDS 1.0.5-A §21.
 *
 * All input positions are ALREADY in the specified owner's local block frame.
 * The external Sable adapter must prove that the raw local point was transformed
 * using the proper collider pose and sublevel owner pose before calling R2/R3.
 * Passing unverified native coordinates is a caller bug; this class never
 * guesses a collider-relative transform or changes a world/chunk.
 */
public final class OwnerLocalTargetCandidates {
    public enum Stage { R1_EXISTING_CALLBACK, R2_COLLIDER_POSE, R3_CROSS_SIDE, R4_SWEPT_PROVISIONAL }
    public enum Reason { VERIFIED, PROVISIONAL_ONLY, OWNER_UNVERIFIED, NO_CALIBRATED_SOURCE,
        NONFINITE_POINT, TARGET_UNRESOLVED, TARGET_AMBIGUOUS, INVALID_CONFIGURATION }
    public record Block(int x,int y,int z) { }
    public record Point(double x,double y,double z) {
        public boolean finite() { return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z); }
    }
    public record Evidence(String ownerKey, Block block, String fingerprint, boolean loaded,
                           boolean solidOrCollidable, boolean exactOwner, boolean protectedBlock) {
        public boolean eligible(String requestedOwner, Block expected) {
            return exactOwner && Objects.equals(ownerKey,requestedOwner) && Objects.equals(block,expected)
                    && loaded && solidOrCollidable && !protectedBlock &&
                    fingerprint!=null && !fingerprint.isBlank();
        }
    }
    @FunctionalInterface public interface Inspector {
        /** Must only read already loaded blocks; must not load chunks or mutate state. */
        Evidence inspect(String ownerKey, Block local);
    }
    public record Request(String ownerKey, boolean ownerVerified,
                          Block existingCallbackTarget, boolean callbackScopeVerified,
                          Point calibratedColliderPoint, boolean colliderTransformVerified,
                          Point otherSideWorldProjectedPoint, boolean otherSideTransformVerified,
                          List<Block> sweptBoundaryCandidates) {
        public Request { sweptBoundaryCandidates = sweptBoundaryCandidates==null ? List.of()
                : List.copyOf(sweptBoundaryCandidates); }
    }
    public record Result(Reason reason,Stage stage,GameImpactCredit.Target target,
                         int checkedCandidates,String detail) {
        public boolean verified() { return reason==Reason.VERIFIED && target != null; }
    }
    private final double epsilon;
    private final int maxPerPoint;
    private final int maxSwept;
    public OwnerLocalTargetCandidates(double epsilon,int maxPerPoint,int maxSwept) {
        if (!Double.isFinite(epsilon)||epsilon<0||epsilon>=0.5||maxPerPoint<1||maxPerPoint>8||
                maxSwept<1||maxSwept>32) throw new IllegalArgumentException("invalid resolver caps");
        this.epsilon=epsilon; this.maxPerPoint=maxPerPoint; this.maxSwept=maxSwept;
    }
    public static OwnerLocalTargetCandidates provisional() { return new OwnerLocalTargetCandidates(1.0/32,8,32); }
    public Result resolve(Request req,Inspector inspector) {
        Objects.requireNonNull(req);Objects.requireNonNull(inspector);
        if(!req.ownerVerified()||req.ownerKey()==null||req.ownerKey().isBlank())
            return result(Reason.OWNER_UNVERIFIED,null,null,0,"OWNER_INDEX_NOT_VALIDATED");
        Result mostInformative=result(Reason.TARGET_UNRESOLVED,null,null,0,"NO_VALIDATED_CANDIDATE");
        if(req.callbackScopeVerified() && req.existingCallbackTarget()!=null) {
            var resolved=validate(req.ownerKey(),Stage.R1_EXISTING_CALLBACK,
                    List.of(req.existingCallbackTarget()),inspector,false);
            if(resolved.verified())return resolved;
            mostInformative=prefer(mostInformative,resolved);
        }
        if(req.colliderTransformVerified() && req.calibratedColliderPoint()!=null) {
            var found=point(req.ownerKey(),Stage.R2_COLLIDER_POSE,req.calibratedColliderPoint(),inspector);
            if(found.verified())return found;
            mostInformative=prefer(mostInformative,found);
        }
        if(req.otherSideTransformVerified() && req.otherSideWorldProjectedPoint()!=null) {
            var found=point(req.ownerKey(),Stage.R3_CROSS_SIDE,
                    req.otherSideWorldProjectedPoint(),inspector);
            if(found.verified())return found;
            mostInformative=prefer(mostInformative,found);
        }
        if(!req.sweptBoundaryCandidates().isEmpty()) {
            Result swept=req.sweptBoundaryCandidates().size()>maxSwept
                    ? result(Reason.TARGET_AMBIGUOUS,Stage.R4_SWEPT_PROVISIONAL,null,0,"SWEPT_CAP_EXCEEDED")
                    : validate(req.ownerKey(),Stage.R4_SWEPT_PROVISIONAL,
                            req.sweptBoundaryCandidates(),inspector,true);
            mostInformative=prefer(mostInformative,swept);
        }
        return mostInformative;
    }
    private static Result prefer(Result older,Result candidate) {
        if(candidate.reason()==Reason.PROVISIONAL_ONLY || candidate.reason()==Reason.TARGET_AMBIGUOUS)
            return candidate;
        return older.reason()==Reason.TARGET_UNRESOLVED? candidate:older;
    }
    private Result point(String owner,Stage stage,Point p,Inspector inspector) {
        if(!p.finite())return result(Reason.NONFINITE_POINT,stage,null,0,"INVALID_POINT");
        if(p.x()<=Integer.MIN_VALUE+2||p.y()<=Integer.MIN_VALUE+2||p.z()<=Integer.MIN_VALUE+2||
            p.x()>=Integer.MAX_VALUE-2||p.y()>=Integer.MAX_VALUE-2||p.z()>=Integer.MAX_VALUE-2)
            return result(Reason.NONFINITE_POINT,stage,null,0,"POINT_OUT_OF_GRID_RANGE");
        int[] xx=options(p.x()),yy=options(p.y()),zz=options(p.z());
        Set<Block> candidates=new LinkedHashSet<>();
        for(int x:xx)for(int y:yy)for(int z:zz)candidates.add(new Block(x,y,z));
        if(candidates.size()>maxPerPoint)
            return result(Reason.TARGET_AMBIGUOUS,stage,null,0,"NEAR_BOUNDARY_CAP_EXCEEDED");
        return validate(owner,stage,new ArrayList<>(candidates),inspector,false);
    }
    private int[] options(double point) {
        int cell=(int)Math.floor(point);
        double fraction=point-cell;
        if(fraction<=epsilon)return new int[]{cell,cell-1};
        if(1-fraction<=epsilon)return new int[]{cell,cell+1};
        return new int[]{cell};
    }
    private Result validate(String owner,Stage stage,List<Block> proposed,
                            Inspector inspector,boolean provisional) {
        int checked=0;
        var accepted=new LinkedHashSet<GameImpactCredit.Target>();
        for(Block block:new LinkedHashSet<>(proposed)) {
            checked++;
            Evidence evidence=inspector.inspect(owner,block);
            if(evidence!=null && evidence.eligible(owner,block))
                accepted.add(new GameImpactCredit.Target(owner,
                        block.x()+","+block.y()+","+block.z(),evidence.fingerprint()));
        }
        if(accepted.isEmpty())return result(Reason.TARGET_UNRESOLVED,stage,null,checked,"NO_LOADED_COLLIDABLE_BLOCK");
        if(accepted.size()>1)return result(Reason.TARGET_AMBIGUOUS,stage,null,checked,"MULTIPLE_REAL_BLOCKS");
        return result(provisional?Reason.PROVISIONAL_ONLY:Reason.VERIFIED,
                stage,accepted.iterator().next(),checked,
                provisional?"R4_REQUIRES_GAME_GROUND_TRUTH":"UNIQUE_OWNER_LOCAL_BLOCK");
    }
    private static Result result(Reason reason,Stage stage,GameImpactCredit.Target target,
                                 int checked,String detail){return new Result(reason,stage,target,checked,detail);}
}
