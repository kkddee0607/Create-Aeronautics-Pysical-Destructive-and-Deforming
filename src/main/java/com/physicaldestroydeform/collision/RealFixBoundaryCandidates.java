package com.physicaldestroydeform.collision;

import java.util.ArrayList;
import java.util.List;

/** R4 candidate enumeration only. No candidate is promoted to a verified target. */
public final class RealFixBoundaryCandidates {
    private RealFixBoundaryCandidates() {}
    public static final int MAX_PER_OWNER=32;
    public static final int MAX_PER_TICK=1024;
    public enum Reason { CANDIDATES, NO_SWEPT_BOUNDS, TOO_BROAD, NONFINITE_OR_OUT_OF_WORLD }
    public record Block(int x,int y,int z) {}
    public record Result(Reason reason,List<Block> blocks) {
        public Result {blocks=List.copyOf(blocks);}
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
        if(nx<=0||ny<=0||nz<=0 || nx>MAX_PER_OWNER||ny>MAX_PER_OWNER||nz>MAX_PER_OWNER||
                nx*ny*nz>Math.min(MAX_PER_OWNER,Math.max(0,remainingChecks)))
            return new Result(Reason.TOO_BROAD,List.of());
        var result=new ArrayList<Block>((int)(nx*ny*nz));
        for(int x=(int)loX;x<=hiX;x++)for(int y=(int)loY;y<=hiY;y++)
            for(int z=(int)loZ;z<=hiZ;z++)result.add(new Block(x,y,z));
        return new Result(Reason.CANDIDATES,result);
    }
}
