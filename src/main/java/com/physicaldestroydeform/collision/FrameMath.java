package com.physicaldestroydeform.collision;

/** Pure Java coordinate geometry. Does not depend on mutable JOML objects or Minecraft. */
public final class FrameMath {
    private FrameMath() {}
    private static final double EPS = 1.0e-12;

    public record Vec(double x, double y, double z) {
        public boolean finite() {
            return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
        }
        public Vec plus(Vec b) { return new Vec(x+b.x, y+b.y, z+b.z); }
        public Vec minus(Vec b) { return new Vec(x-b.x, y-b.y, z-b.z); }
        public Vec times(double t) { return new Vec(x*t, y*t, z*t); }
        public double dot(Vec b) { return x*b.x+y*b.y+z*b.z; }
        public Vec cross(Vec b) {
            return new Vec(y*b.z-z*b.y, z*b.x-x*b.z, x*b.y-y*b.x);
        }
        public double squareLength() { return x*x+y*y+z*z; }
        public Vec unit() {
            if (!finite()) throw new IllegalArgumentException("nonfinite direction");
            double len = Math.sqrt(squareLength());
            if (!Double.isFinite(len) || len < EPS) throw new IllegalArgumentException("zero direction");
            return times(1.0/len);
        }
    }

    /** Sable's Pose3dc uses (local - rotationPoint) * scale, then rotation, then position. */
    public record Pose(Vec position, Vec rotationPoint, Vec scale, double qx, double qy, double qz, double qw) {
        public boolean valid() {
            if (position == null || !position.finite() || rotationPoint == null || !rotationPoint.finite()
                    || scale == null || !scale.finite()
                    || Math.abs(scale.x)<EPS || Math.abs(scale.y)<EPS || Math.abs(scale.z)<EPS) return false;
            double length = qx*qx+qy*qy+qz*qz+qw*qw;
            return Double.isFinite(length) && length>EPS;
        }
        private Vec rotated(Vec v, boolean inverse) {
            if (!valid() || v==null || !v.finite()) throw new IllegalArgumentException("invalid frame vector");
            double inv = 1.0/Math.sqrt(qx*qx+qy*qy+qz*qz+qw*qw);
            Vec q = new Vec(qx*inv, qy*inv, qz*inv);
            double w = qw*inv;
            if (inverse) q=q.times(-1);
            Vec t=q.cross(v).times(2);
            return v.plus(t.times(w)).plus(q.cross(t));
        }
        public Vec toWorldPoint(Vec local) {
            if (local==null || !local.finite()) throw new IllegalArgumentException("invalid point");
            Vec l=local.minus(rotationPoint);
            return rotated(new Vec(l.x*scale.x, l.y*scale.y, l.z*scale.z),false).plus(position);
        }
        public Vec toLocalPoint(Vec world) {
            if (world==null || !world.finite()) throw new IllegalArgumentException("invalid point");
            Vec r=rotated(world.minus(position),true);
            return new Vec(r.x/scale.x, r.y/scale.y, r.z/scale.z).plus(rotationPoint);
        }
        public Vec toWorldVector(Vec local) {
            if(local==null || !local.finite()) throw new IllegalArgumentException("invalid vector");
            return rotated(new Vec(local.x*scale.x,local.y*scale.y,local.z*scale.z),false);
        }
        public Vec toLocalVector(Vec world) {
            Vec r=rotated(world,true);
            return new Vec(r.x/scale.x,r.y/scale.y,r.z/scale.z);
        }
        /** Correct inverse-transpose normal under non-uniform scaling, followed by normalization. */
        public Vec toWorldNormal(Vec localNormal) {
            if(localNormal==null || !localNormal.finite()) throw new IllegalArgumentException("invalid normal");
            return rotated(new Vec(localNormal.x/scale.x,localNormal.y/scale.y,
                    localNormal.z/scale.z),false).unit();
        }
        public Vec toLocalNormal(Vec worldNormal) {
            Vec r=rotated(worldNormal,true);
            return new Vec(r.x*scale.x,r.y*scale.y,r.z*scale.z).unit();
        }
    }

    /** A valid body frame is not by itself proof of the meaning of a callback raw position. */
    public static Vec contactVelocity(Vec linearWorld, Vec angularWorld, Vec contactWorld, Vec centerWorld) {
        if(linearWorld==null || !linearWorld.finite() || angularWorld==null || !angularWorld.finite()
                || contactWorld==null || !contactWorld.finite()
                || centerWorld==null || !centerWorld.finite())
            throw new IllegalArgumentException("invalid same-epoch contact velocity input");
        Vec answer=linearWorld.plus(angularWorld.cross(contactWorld.minus(centerWorld)));
        if(!answer.finite())throw new IllegalArgumentException("nonfinite contact velocity");
        return answer;
    }
}
