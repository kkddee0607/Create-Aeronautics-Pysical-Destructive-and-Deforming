package com.physicaldestroydeform.collision;

import java.util.Objects;

/** Independent world-space broad-phase evidence; overlap never proves contact. */
public final class RealFixBoundsEvidence {
    private RealFixBoundsEvidence() { }
    public static final double SEPARATION_THRESHOLD = 0.25;
    public static final double APPROACH_DELTA = 1.0 / 32.0;

    public record Box(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {
        public Box {
            if(!Double.isFinite(minX)||!Double.isFinite(minY)||!Double.isFinite(minZ)
                    ||!Double.isFinite(maxX)||!Double.isFinite(maxY)||!Double.isFinite(maxZ)
                    ||minX>maxX||minY>maxY||minZ>maxZ)
                throw new IllegalArgumentException("invalid world-space bounds");
        }
        public static Box block(int x,int y,int z) {return new Box(x,y,z,x+1.0,y+1.0,z+1.0);}
        public double gap(Box other) {
            Objects.requireNonNull(other);
            double x=Math.max(0,Math.max(other.minX-maxX,minX-other.maxX));
            double y=Math.max(0,Math.max(other.minY-maxY,minY-other.maxY));
            double z=Math.max(0,Math.max(other.minZ-maxZ,minZ-other.maxZ));
            return Math.sqrt(x*x+y*y+z*z);
        }
        public boolean separatedFrom(Box other) {return gap(other)>=SEPARATION_THRESHOLD;}
    }
    /** Finite world-space swept broad-phase union for R4 diagnostics only.
     * A bound is never evidence of an exact contact or a world block. */
    public static Box swept(Box previous,Box current) {
        if(current==null)return null;
        if(previous==null)return current;
        return new Box(Math.min(previous.minX(),current.minX()),
                Math.min(previous.minY(),current.minY()),Math.min(previous.minZ(),current.minZ()),
                Math.max(previous.maxX(),current.maxX()),
                Math.max(previous.maxY(),current.maxY()),Math.max(previous.maxZ(),current.maxZ()));
    }
    /** Validated decrease in independent broad-phase gap. Never infer direction from a force magnitude. */
    public static boolean approaching(Box aPrevious,Box bPrevious,Box aNow,Box bNow) {
        if(aPrevious==null||bPrevious==null||aNow==null||bNow==null) return false;
        return aPrevious.gap(bPrevious)-aNow.gap(bNow)>=APPROACH_DELTA;
    }
}
