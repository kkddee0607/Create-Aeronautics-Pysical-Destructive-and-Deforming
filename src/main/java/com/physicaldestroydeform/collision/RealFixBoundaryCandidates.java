package com.physicaldestroydeform.collision;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;

/** R4 candidate enumeration only. No candidate is promoted to a verified target. */
public final class RealFixBoundaryCandidates {
    private RealFixBoundaryCandidates() {}
    public static final int MAX_PER_OWNER=32;
    public static final int MAX_PER_TICK=1024;
    public enum Reason { CANDIDATES, NO_SWEPT_BOUNDS, TOO_BROAD, NONFINITE_OR_OUT_OF_WORLD }
    public record Block(int x,int y,int z) {}
    public record Result(Reason reason,List<Block> blocks,int uniqueCells,
                         int sourcePoints,int failedPointIndex,String detail) {
        public Result(Reason reason,List<Block> blocks) {
            this(reason,blocks,blocks.size(),0,-1,"NONE");
        }
        public Result {blocks=List.copyOf(blocks);}
    }
    /** R4: native world-side point anchors a narrow loaded-cell band; plot
     * AABB is a broad-phase exclusion only. This NEVER proves block contact. */
    public static Result nearPlanePoint(RealFixBoundsEvidence.Box moving,
                                        double x,double y,double z,int remainingChecks) {
        if(moving==null)return new Result(Reason.NO_SWEPT_BOUNDS,List.of());
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z) ||
                x<=Integer.MIN_VALUE+2||y<=Integer.MIN_VALUE+2||z<=Integer.MIN_VALUE+2||
                x>=Integer.MAX_VALUE-2||y>=Integer.MAX_VALUE-2||z>=Integer.MAX_VALUE-2)
            return new Result(Reason.NONFINITE_OR_OUT_OF_WORLD,List.of());
        if(x<moving.minX()-1||x>moving.maxX()+1 || y<moving.minY()-1||y>moving.maxY()+1 ||
                z<moving.minZ()-1||z>moving.maxZ()+1)
            return new Result(Reason.NO_SWEPT_BOUNDS,List.of());
        int[] xx=nearAxis(x),yy=nearAxis(y),zz=nearAxis(z);
        int count=xx.length*yy.length*zz.length;
        if(count>Math.min(MAX_PER_OWNER,Math.max(0,remainingChecks)))
            return new Result(Reason.TOO_BROAD,List.of(),count,1,0,
                    "POINT_CELL_COUNT_EXCEEDS_BUDGET pointCells="+count+
                    " remaining="+remainingChecks+" ownerCap="+MAX_PER_OWNER);
        var blocks=new ArrayList<Block>(count);
        for(int a:xx)for(int b:yy)for(int c:zz)blocks.add(new Block(a,b,c));
        return new Result(Reason.CANDIDATES,blocks);
    }
    /** R4 diagnostic only: multiple manifold points may cover different
     * legitimate contact regions. The bounded UNION is still provisional, and
     * a lone voxel in it is never authoritative without independent evidence. */
    public static Result nearContactPoints(RealFixBoundsEvidence.Box moving,
                                           List<FrameMath.Vec> points,int remainingChecks) {
        if(moving==null)return new Result(Reason.NO_SWEPT_BOUNDS,List.of());
        if(points==null || points.isEmpty())return new Result(Reason.NO_SWEPT_BOUNDS,List.of());
        var unique=new LinkedHashSet<Block>();
        for(int i=0;i<points.size();i++) {
            var point=points.get(i);
            if(point==null)return new Result(Reason.NONFINITE_OR_OUT_OF_WORLD,List.of(),
                    unique.size(),points.size(),i,"NULL_WORLD_CONTACT_POINT");
            Result next=nearPlanePoint(moving,point.x(),point.y(),point.z(),remainingChecks);
            if(next.reason()!=Reason.CANDIDATES)
                return new Result(next.reason(),List.of(),Math.max(unique.size(),next.uniqueCells()),
                        points.size(),i,"CONTACT_POINT="+i+" "+next.detail());
            unique.addAll(next.blocks());
            if(unique.size()>Math.min(MAX_PER_OWNER,Math.max(0,remainingChecks)))
                return new Result(Reason.TOO_BROAD,List.of(),unique.size(),points.size(),i,
                        "UNIQUE_CONTACT_CELLS_EXCEED_BUDGET unique="+unique.size()+
                        " remaining="+remainingChecks+" ownerCap="+MAX_PER_OWNER);
        }
        return new Result(Reason.CANDIDATES,List.copyOf(unique),unique.size(),
                points.size(),-1,"BOUNDED_UNION");
    }
    private static int[] nearAxis(double value) {
        int low=(int)Math.floor(value);
        double f=value-low;
        if(f<1.0/32.0)return new int[]{low,low-1};
        if(f>1-1.0/32.0)return new int[]{low,low+1};
        return new int[]{low};
    }
    public static Result nearOverlap(RealFixBoundsEvidence.Box source,int remainingChecks) {
        if(source==null)return new Result(Reason.NO_SWEPT_BOUNDS,List.of());
        // Sample only the local world-grid footprint of a real Sable world AABB;
        // NEVER expand to the full world or trigger chunk loading.
        double loX=Math.floor(source.minX()-1.0/32.0),hiX=Math.floor(source.maxX()+1.0/32.0);
        double loY=Math.floor(source.minY()-1.0/32.0),hiY=Math.floor(source.maxY()+1.0/32.0);
        double loZ=Math.floor(source.minZ()-1.0/32.0),hiZ=Math.floor(source.maxZ()+1.0/32.0);
        if(loX<Integer.MIN_VALUE+2||loY<Integer.MIN_VALUE+2||loZ<Integer.MIN_VALUE+2||
           hiX>Integer.MAX_VALUE-2||hiY>Integer.MAX_VALUE-2||hiZ>Integer.MAX_VALUE-2)
            return new Result(Reason.NONFINITE_OR_OUT_OF_WORLD,List.of());
        long nx=(long)(hiX-loX+1),ny=(long)(hiY-loY+1),nz=(long)(hiZ-loZ+1);
        if(nx<=0||ny<=0||nz<=0) return new Result(Reason.TOO_BROAD,List.of());
        if(nx>MAX_PER_OWNER||ny>MAX_PER_OWNER||nz>MAX_PER_OWNER||
                nx*ny*nz>Math.min(MAX_PER_OWNER,Math.max(0,remainingChecks)))
            return new Result(Reason.TOO_BROAD,List.of());
        var result=new ArrayList<Block>((int)(nx*ny*nz));
        for(int x=(int)loX;x<=hiX;x++)for(int y=(int)loY;y<=hiY;y++)
            for(int z=(int)loZ;z<=hiZ;z++)result.add(new Block(x,y,z));
        return new Result(Reason.CANDIDATES,result);
    }
}
