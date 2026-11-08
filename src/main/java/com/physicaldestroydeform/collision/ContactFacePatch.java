package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import net.minecraft.core.BlockPos;

/**
 * Recover an independently shape-verified, axis-aligned contact FACE from
 * sparse Sable corner witnesses. Sable 2.0.6 intentionally discards Face/Face
 * and Edge/Face collider pairs; their absence is not evidence of empty space.
 *
 * No block is granted credit without an owner-local shape test at the common
 * native contact plane. No cross-substep Plot-to-World projection is performed.
 */
final class ContactFacePatch {
    private ContactFacePatch() {}
    static final int MAX_AREA = 10_000; // 100x100 contact-face policy, never a volumetric scan
    record Result(List<GameImpactCredit.Target> added, int attempted, String reason) {
        Result { added=List.copyOf(added); }
    }
    /** Caller MUST shape-validate every offered block/point pair; null denies it. */
    static Result reconstruct(String dimension, String owner,BlockPos gridOffset,
                              Collection<GameImpactCredit.Target> verified,
                              List<NativeCollisionTelemetry.Contact> records,
                              BiFunction<BlockPos,FrameMath.Vec,GameImpactCredit.Target> shape) {
        Objects.requireNonNull(gridOffset);Objects.requireNonNull(verified);
        Objects.requireNonNull(records);Objects.requireNonNull(shape);
        if(verified.size()<4 || records.size()<4)return new Result(List.of(),0,"INSUFFICIENT_WITNESSES");
        int axis=-1,sign=0,corroborated=0;
        double plane=0,minU=Double.POSITIVE_INFINITY,maxU=Double.NEGATIVE_INFINITY;
        double minV=Double.POSITIVE_INFINITY,maxV=Double.NEGATIVE_INFINITY;
        for(var record:records) {
            int side=SableConsumerTargets.sourceSide(owner,record,dimension);
            if(side==0 || !SableConsumerTargets.sourceRecordValid(record))continue;
            var point=SableConsumerTargets.candidate(record,side==1);
            if(point==null || !point.finite())continue;
            double[] normal=side==1?new double[]{record.ax(),record.ay(),record.az()}:
                    new double[]{record.bx(),record.by(),record.bz()};
            int dominant=0;
            for(int i=1;i<3;i++)if(Math.abs(normal[i])>Math.abs(normal[dominant]))dominant=i;
            double magnitude=Math.sqrt(normal[0]*normal[0]+normal[1]*normal[1]+normal[2]*normal[2]);
            if(magnitude<1e-9 || Math.abs(normal[dominant])/magnitude<0.98)
                return new Result(List.of(),0,"NOT_AXIS_ALIGNED");
            int direction=normal[dominant]>=0?1:-1;
            if(axis!=-1 && (axis!=dominant || sign!=direction))
                return new Result(List.of(),0,"INCONSISTENT_NORMALS");
            axis=dominant;sign=direction;
            // Plot target keys are centered-relative, whereas Sable native
            // witness points use the allocated plot storage grid.
            double[] c={point.x()-gridOffset.getX(),point.y()-gridOffset.getY(),
                    point.z()-gridOffset.getZ()};
            if(corroborated>0 && Math.abs(c[axis]-plane/corroborated)>0.125)
                return new Result(List.of(),0,"NOT_COPLANAR");
            plane+=c[axis];corroborated++;
            int u=(axis+1)%3,v=(axis+2)%3;
            minU=Math.min(minU,c[u]);maxU=Math.max(maxU,c[u]);
            minV=Math.min(minV,c[v]);maxV=Math.max(maxV,c[v]);
        }
        if(corroborated<4)return new Result(List.of(),0,"NO_COMMON_FACE");
        plane/=corroborated;
        int u=(axis+1)%3,v=(axis+2)%3;
        final int faceAxis=axis;
        var layers=new HashMap<Integer,List<int[]>>();
        var existing=new java.util.HashSet<String>();
        for(var target:verified) {
            String[] parts=target.localBlock().split(",",-1);
            if(parts.length!=3)continue;
            try {
                int[] c={Integer.parseInt(parts[0]),Integer.parseInt(parts[1]),Integer.parseInt(parts[2])};
                layers.computeIfAbsent(c[faceAxis],k->new ArrayList<>()).add(c);
                existing.add(target.localBlock());
            }catch(NumberFormatException bad) {return new Result(List.of(),0,"BAD_VERIFIED_COORDINATE");}
        }
        List<int[]> layer=List.of();
        for(var candidate:layers.values())if(candidate.size()>layer.size())layer=candidate;
        if(layer.size()<4)return new Result(List.of(),0,"NO_FOUR_CORNERS");
        int minX=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,minY=Integer.MAX_VALUE,maxY=Integer.MIN_VALUE;
        var anchored=new java.util.HashSet<String>();
        for(var p:layer) {
            minX=Math.min(minX,p[u]);maxX=Math.max(maxX,p[u]);
            minY=Math.min(minY,p[v]);maxY=Math.max(maxY,p[v]);
            anchored.add(p[u]+","+p[v]);
        }
        long width=(long)maxX-minX+1,height=(long)maxY-minY+1;
        if(width<3||height<3||width*height>MAX_AREA)return new Result(List.of(),0,"PATCH_BOUNDS");
        if(!anchored.contains(minX+","+minY)||!anchored.contains(minX+","+maxY)
                ||!anchored.contains(maxX+","+minY)||!anchored.contains(maxX+","+maxY))
            return new Result(List.of(),0,"CORNERS_UNPROVEN");
        // The native witnesses must actually span the corner cells. A few
        // coincident contacts cannot authorize a fabricated broad rectangle.
        if(maxU-minU < width-2.0 || maxV-minV < height-2.0)
            return new Result(List.of(),0,"WITNESS_SPAN_TOO_SMALL");
        var added=new ArrayList<GameImpactCredit.Target>();
        int attempted=0;
        int fixed=layer.getFirst()[axis];
        for(int a=minX;a<=maxX;a++)for(int b=minY;b<=maxY;b++) {
            int[] c=new int[3];c[axis]=fixed;c[u]=a;c[v]=b;
            String key=c[0]+","+c[1]+","+c[2];
            if(existing.contains(key))continue;
            double[] p={c[0]+0.5,c[1]+0.5,c[2]+0.5};p[axis]=plane;
            attempted++;
            var found=shape.apply(new BlockPos(c[0],c[1],c[2]),
                    new FrameMath.Vec(p[0],p[1],p[2]));
            if(found!=null && found.ownerKey().equals(owner) && found.localBlock().equals(key))
                added.add(found);
        }
        return new Result(added,attempted,"SHAPE_VERIFIED_FACE");
    }
}
