package com.physicaldestroydeform.collision;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.physicaldestroydeform.collision.FrameMath.*;

/** Immutable Sable physics substep ownership map and untrusted callback-frame evidence. */
public final class CollisionFrame {
    private CollisionFrame() {}
    public enum Space { STATIC_WORLD, SABLE_PLOT, UNKNOWN }
    public enum Phase { PRE_SOLVER, POST_SOLVER, UNSCOPED }
    /** No candidate becomes PRE_SOLVER_VALIDATED without passing the in-game G-COL-02 gate. */
    public enum Quality { PRE_SOLVER_VALIDATED, EQUIVALENT_VALIDATED, DIAGNOSTIC_ONLY, INVALID }

    public record Body(UUID plotId, int minX, int maxX, int minZ, int maxZ,
                       Pose pose, Vec linearRaw, Vec angularRaw, Vec centerRaw,
                       boolean handleValid) {
        public boolean contains(int x, int z) { return minX<=x && x<=maxX && minZ<=z && z<=maxZ; }
        public boolean valid() {
            return plotId!=null && minX<=maxX && minZ<=maxZ && pose!=null && pose.valid()
                    && handleValid && linearRaw!=null && linearRaw.finite()
                    && angularRaw!=null && angularRaw.finite();
        }
    }
    public record BlockTarget(Space space, String dimension, UUID stablePlotId,
                              int x,int y,int z, boolean valid, String provenance) {
        public boolean plot() { return valid && space==Space.SABLE_PLOT && stablePlotId!=null; }
    }
    public record Evidence(long epoch, long serverTick, String dimension, Phase phase,
                           BlockTarget hit, BlockTarget other, Vec contactRaw,
                           Vec contactWorldHypothesis, Vec otherWorldHypothesis,
                           Body hitBody, Body otherBody, Quality quality, String reason) {
        public static Evidence invalid(String message) {
            return new Evidence(-1,-1,"UNKNOWN",Phase.UNSCOPED,null,null,null,null,null,null,null,
                    Quality.INVALID,message);
        }
        /** Not authorized to mutate blocks during 0.2.13. */
        public boolean canMutateWorld() { return false; }
    }

    public record Snapshot(long epoch, long serverTick, String dimension, Phase phase,
                           List<Body> bodies, boolean containerAvailable) {
        public Snapshot { bodies=List.copyOf(bodies); }

        public BlockTarget resolve(int x,int y,int z) {
            if (!containerAvailable || dimension==null || dimension.isBlank())
                return new BlockTarget(Space.UNKNOWN,dimension,null,x,y,z,false,"NO_CONTAINER");
            Body found=null;
            for(Body body:bodies) if(body.contains(x,z)) {
                if(found!=null) return new BlockTarget(Space.UNKNOWN,dimension,null,x,y,z,false,"AMBIGUOUS_PLOT");
                found=body;
            }
            if(found==null) return new BlockTarget(Space.STATIC_WORLD,dimension,null,x,y,z,true,
                    "WORLD_SPACE_COORDINATE_HYPOTHESIS");
            if(!found.valid())return new BlockTarget(Space.UNKNOWN,dimension,found.plotId(),x,y,z,
                    false,"MISSING_VALID_POSE_OR_HANDLE");
            return new BlockTarget(Space.SABLE_PLOT,dimension,found.plotId(),x,y,z,true,
                    "PLOT_SPACE_COORDINATE_HYPOTHESIS");
        }

        /** Keep coordinates and velocity scalar raw; both target positions are independent. */
        public Evidence observe(int hx,int hy,int hz, boolean hasOther,int ox,int oy,int oz,
                                double px,double py,double pz,double rawSignedVelocity) {
            BlockTarget a=resolve(hx,hy,hz);
            BlockTarget b=hasOther?resolve(ox,oy,oz):null;
            Vec raw=new Vec(px,py,pz);
            if (!a.valid || (b!=null && !b.valid) || !raw.finite()
                    || !Double.isFinite(rawSignedVelocity))
                return new Evidence(epoch,serverTick,dimension,phase,a,b,raw,null,null,null,null,
                        Quality.INVALID,"BAD_OWNER_OR_NONFINITE_RAW_ARGUMENT");
            Body hitBody=findBody(a), otherBody=b==null?null:findBody(b);
            if ((a.plot() && (hitBody==null || !hitBody.valid())) ||
                    (b!=null && b.plot() && (otherBody==null || !otherBody.valid())))
                return new Evidence(epoch,serverTick,dimension,phase,a,b,raw,null,null,
                        hitBody,otherBody,Quality.INVALID,"PLOT_NOT_IN_SNAPSHOT");
            // Two hypotheses, not one assumed common frame. Both are required for plot/plot cases.
            Vec fromHit=hitBody==null?raw:hitBody.pose().toWorldPoint(raw);
            Vec fromOther=b==null?null:otherBody==null?raw:otherBody.pose().toWorldPoint(raw);
            if (!fromHit.finite() || (fromOther!=null && !fromOther.finite()))
                return new Evidence(epoch,serverTick,dimension,phase,a,b,raw,null,null,
                        hitBody,otherBody,Quality.INVALID,"NONFINITE_TRANSFORM");
            return new Evidence(epoch,serverTick,dimension,phase,a,b,raw,fromHit,fromOther,
                    hitBody,otherBody,Quality.DIAGNOSTIC_ONLY,"RAW_IMPACT_FRAME_UNVERIFIED");
        }
        private Body findBody(BlockTarget target) {
            if(target==null || !target.plot())return null;
            Body match=null;
            for(Body body:bodies)if(body.plotId().equals(target.stablePlotId())){
                if(match!=null)return null; // Duplicate stable owner IDs are never resolved by list order.
                match=body;
            }
            return match;

        }
    }
    public static boolean sameStep(Snapshot a,Snapshot b) {
        return a!=null && b!=null && a.epoch==b.epoch && a.serverTick==b.serverTick
                && Objects.equals(a.dimension,b.dimension) && a.phase==Phase.PRE_SOLVER
                && b.phase==Phase.PRE_SOLVER;
    }
}
