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
                    && angularRaw!=null && angularRaw.finite()
                    && centerRaw!=null && centerRaw.finite();
        }
    }
    public record BlockTarget(Space space, String dimension, UUID stablePlotId,
                              int x,int y,int z, boolean valid, String provenance) {
        public boolean plot() { return valid && space==Space.SABLE_PLOT && stablePlotId!=null; }
    }
    public record Evidence(long epoch, long serverTick, String dimension, Phase phase,
                           BlockTarget hit, BlockTarget other, Vec contactRaw,
                           Vec contactWorldHypothesis, Vec otherContactLocalHypothesis,
                           Body hitBody, Body otherBody, Quality quality, String reason) {
        public static Evidence invalid(String message) {
            return new Evidence(-1,-1,"UNKNOWN",Phase.UNSCOPED,null,null,null,null,null,null,null,
                    Quality.INVALID,message);
        }
        /** Evidence is promoted only after an independent opposite-side contact has been matched. */
        Evidence validated() {
            if (phase != Phase.PRE_SOLVER || quality != Quality.DIAGNOSTIC_ONLY
                    || !"OWNER_FRAME_GEOMETRY_PLAUSIBLE".equals(reason))
                throw new IllegalStateException("cannot validate unpaired or invalid contact");
            return new Evidence(epoch, serverTick, dimension, phase, hit, other, contactRaw,
                    contactWorldHypothesis, otherContactLocalHypothesis, hitBody, otherBody,
                    Quality.PRE_SOLVER_VALIDATED, "RECIPROCAL_SAME_EPOCH_WORLD_CONTACT");
        }
        /** Only a confirmed independent pair supplies a validated world position. */
        public Vec confirmedWorldContact() {
            return quality == Quality.PRE_SOLVER_VALIDATED ? contactWorldHypothesis : null;
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
            if (phase!=Phase.PRE_SOLVER)
                return new Evidence(epoch,serverTick,dimension,phase,a,b,raw,null,null,null,null,
                        Quality.INVALID,"NOT_PRE_SOLVER");
            if (!a.valid || (b!=null && !b.valid) || !raw.finite()
                    || !Double.isFinite(rawSignedVelocity))
                return new Evidence(epoch,serverTick,dimension,phase,a,b,raw,null,null,null,null,
                        Quality.INVALID,"BAD_OWNER_OR_NONFINITE_RAW_ARGUMENT");
            Body hitBody=findBody(a), otherBody=b==null?null:findBody(b);
            if ((a.plot() && (hitBody==null || !hitBody.valid())) ||
                    (b!=null && b.plot() && (otherBody==null || !otherBody.valid())))
                return new Evidence(epoch,serverTick,dimension,phase,a,b,raw,null,null,
                        hitBody,otherBody,Quality.INVALID,"PLOT_NOT_IN_SNAPSHOT");
            // Sable passes the impact in the HIT block's coordinate frame.
            // Never interpret that same raw point directly in the OTHER body's frame.
            Vec world=hitBody==null?raw:hitBody.pose().toWorldPoint(raw);
            Vec otherLocal=b==null?null:otherBody==null?world:otherBody.pose().toLocalPoint(world);
            if (!world.finite() || (otherLocal!=null && !otherLocal.finite()))
                return new Evidence(epoch,serverTick,dimension,phase,a,b,raw,null,null,
                        hitBody,otherBody,Quality.INVALID,"NONFINITE_TRANSFORM");
            // A raw callback position in the wrong frame must never become a usable
            // target. A 2-block margin allows shapes crossing block boundaries.
            // In particular, untransformed static-world positions masquerading as
            // plot-local coordinates would otherwise jump millions of blocks.
            boolean nearHit=nearBlock(raw,a,2.0);
            boolean nearOther=b==null || nearBlock(otherLocal,b,2.0);
            if (!nearHit || !nearOther)
                return new Evidence(epoch,serverTick,dimension,phase,a,b,raw,world,otherLocal,
                        hitBody,otherBody,Quality.INVALID,"CONTACT_OUTSIDE_OWNER_BOUNDS");
            return new Evidence(epoch,serverTick,dimension,phase,a,b,raw,world,otherLocal,
                    hitBody,otherBody,Quality.DIAGNOSTIC_ONLY,"OWNER_FRAME_GEOMETRY_PLAUSIBLE");
        }
        /** Euclidean distance to the nearest point on the block's unit AABB. */
        private static boolean nearBlock(Vec p,BlockTarget b,double margin) {
            if(p==null || b==null || !p.finite())return false;
            double dx=Math.max(Math.max(b.x()-p.x(),p.x()-(b.x()+1.0)),0.0);
            double dy=Math.max(Math.max(b.y()-p.y(),p.y()-(b.y()+1.0)),0.0);
            double dz=Math.max(Math.max(b.z()-p.z(),p.z()-(b.z()+1.0)),0.0);
            return dx*dx+dy*dy+dz*dz <= margin*margin;
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
    /** Independent, opposite-side callback samples: compare world points only when owners and epoch match. */
    public static boolean reciprocal(Evidence a,Evidence b) {
        return a!=null && b!=null && a.epoch()==b.epoch()
                && a.serverTick()==b.serverTick() && Objects.equals(a.dimension(),b.dimension())
                && a.phase()==Phase.PRE_SOLVER && b.phase()==Phase.PRE_SOLVER
                && a.quality()!=Quality.INVALID && b.quality()!=Quality.INVALID
                && a.hit()!=null && a.other()!=null && b.hit()!=null && b.other()!=null
                && a.contactWorldHypothesis()!=null && b.contactWorldHypothesis()!=null
                && !a.hit().equals(a.other())
                && a.hit().equals(b.other()) && a.other().equals(b.hit());
    }
    public static double contactSeparation(Evidence a,Evidence b) {
        if (!reciprocal(a,b) || a.contactWorldHypothesis()==null || b.contactWorldHypothesis()==null)
            throw new IllegalArgumentException("not a reciprocal same-epoch contact pair");
        double distance=Math.sqrt(a.contactWorldHypothesis().minus(b.contactWorldHypothesis()).squareLength());
        if (!Double.isFinite(distance))throw new IllegalArgumentException("nonfinite contact separation");
        return distance;
    }
    public static boolean sameStep(Snapshot a,Snapshot b) {
        return a!=null && b!=null && a.epoch==b.epoch && a.serverTick==b.serverTick
                && Objects.equals(a.dimension,b.dimension) && a.phase==Phase.PRE_SOLVER
                && b.phase==Phase.PRE_SOLVER;
    }
}
