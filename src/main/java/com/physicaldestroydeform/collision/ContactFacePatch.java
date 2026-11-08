package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import net.minecraft.core.BlockPos;

/**
 * Restore a broad, axis-aligned contact FACE from Sable's sparse corner
 * witnesses. The native four corners bound the face; independently verified
 * owner-local collision shapes decide EVERY block in the interior.
 *
 * No Sable callback is replaced, no contact adds F/Q, and no current Plot pose
 * is used to reproject an old native point into the other owner. This is a
 * conservative shape-verified damage-target recovery, not a solver manifold.
 */
final class ContactFacePatch {
    private ContactFacePatch() {}
    static final int MAX_AREA = 10_000; // 100x100 surface; never scan a volume
    private static final double PLANE_EPS = 0.125;
    private static final double GRID_EPS = 1e-6;
    private static final double CORNER_EPS = 0.75;

    record Result(List<GameImpactCredit.Target> added, int attempted, String reason) {
        Result { added = List.copyOf(added); }
    }
    private record Witness(int axis, int sign, double plane, double u, double v) {}
    private record Face(int axis, int sign, double plane, int minU, int maxU,
                        int minV, int maxV, double spanArea) {}

    /** Caller MUST shape-validate the offered owner/position/point pair. */
    static Result reconstruct(String dimension, String owner, BlockPos gridOffset,
                              Collection<GameImpactCredit.Target> verified,
                              List<NativeCollisionTelemetry.Contact> records,
                              BiFunction<BlockPos,FrameMath.Vec,GameImpactCredit.Target> shape) {
        Objects.requireNonNull(dimension);Objects.requireNonNull(owner);
        Objects.requireNonNull(gridOffset);Objects.requireNonNull(verified);
        Objects.requireNonNull(records);Objects.requireNonNull(shape);
        if(records.size()<4 || verified.isEmpty())return new Result(List.of(),0,"INSUFFICIENT_WITNESSES");

        var observations=new ArrayList<Witness>();
        boolean sawOblique=false;
        for(var record:records) {
            if(!SableConsumerTargets.sourceRecordValid(record))continue;
            int side=SableConsumerTargets.sourceSide(owner,record,dimension);
            if(side==0)continue;
            var point=SableConsumerTargets.candidate(record,side==1);
            if(point==null || !point.finite())continue;
            double[] normal=side==1
                    ?new double[]{record.ax(),record.ay(),record.az()}
                    :new double[]{record.bx(),record.by(),record.bz()};
            int axis=0;
            for(int i=1;i<3;i++)if(Math.abs(normal[i])>Math.abs(normal[axis]))axis=i;
            double magnitude=Math.sqrt(normal[0]*normal[0]+normal[1]*normal[1]+normal[2]*normal[2]);
            if(magnitude<1e-9 || Math.abs(normal[axis])/magnitude<0.98) {
                sawOblique=true;continue; // unrelated sloped points must not veto a proven flat face
            }
            double[] pointLocal={point.x()-gridOffset.getX(),point.y()-gridOffset.getY(),
                    point.z()-gridOffset.getZ()};
            int u=(axis+1)%3,v=(axis+2)%3;
            observations.add(new Witness(axis,normal[axis]>=0?1:-1,
                    pointLocal[axis],pointLocal[u],pointLocal[v]));
        }
        if(observations.size()<4)return new Result(List.of(),0,
                sawOblique?"NOT_AXIS_ALIGNED":"INSUFFICIENT_WITNESSES");

        // Each witness is a possible contact-plane seed. Other nearby planes,
        // corners of a different face, and oblique contacts are ignored rather
        // than vetoing a valid 5x5 face. Strict four-quadrant coverage prevents
        // diagonal/two-corner records from manufacturing a rectangle.
        // Group compatible native planes once instead of searching every
        // witness against every other witness (large Rapier batches can be 2048).
        var groups=new ArrayList<List<Witness>>();
        for(var w:observations) {
            List<Witness> match=null;
            for(var group:groups) {
                var first=group.getFirst();
                if(first.axis()==w.axis() && first.sign()==w.sign()
                        && Math.abs(first.plane()-w.plane())<=PLANE_EPS) {
                    match=group;break;
                }
            }
            if(match==null){match=new ArrayList<>();groups.add(match);}
            match.add(w);
        }
        Face chosen=null;
        for(var same:groups) {
            var base=same.getFirst();
            double planeSum=0,minU=Double.POSITIVE_INFINITY,maxU=Double.NEGATIVE_INFINITY;
            double minV=Double.POSITIVE_INFINITY,maxV=Double.NEGATIVE_INFINITY;
            for(var w:same) {
                planeSum+=w.plane();
                minU=Math.min(minU,w.u());maxU=Math.max(maxU,w.u());
                minV=Math.min(minV,w.v());maxV=Math.max(maxV,w.v());
            }
            if(same.size()<4)continue;
            boolean[] corners=new boolean[4];
            for(var w:same) {
                if(w.u()-minU<=CORNER_EPS && w.v()-minV<=CORNER_EPS)corners[0]=true;
                if(w.u()-minU<=CORNER_EPS && maxV-w.v()<=CORNER_EPS)corners[1]=true;
                if(maxU-w.u()<=CORNER_EPS && w.v()-minV<=CORNER_EPS)corners[2]=true;
                if(maxU-w.u()<=CORNER_EPS && maxV-w.v()<=CORNER_EPS)corners[3]=true;
            }
            if(!(corners[0]&&corners[1]&&corners[2]&&corners[3]))continue;
            int u0=(int)Math.floor(minU+GRID_EPS);
            int u1=(int)Math.ceil(maxU-GRID_EPS)-1;
            int v0=(int)Math.floor(minV+GRID_EPS);
            int v1=(int)Math.ceil(maxV-GRID_EPS)-1;
            long width=(long)u1-u0+1,height=(long)v1-v0+1;
            if(width<3||height<3||width*height>MAX_AREA)continue;
            if(maxU-minU<width-2.0 || maxV-minV<height-2.0)continue;
            double area=(maxU-minU)*(maxV-minV);
            if(chosen==null || area>chosen.spanArea())
                chosen=new Face(base.axis(),base.sign(),planeSum/same.size(),u0,u1,v0,v1,area);
        }
        if(chosen==null)return new Result(List.of(),0,"NO_CORROBORATED_FACE");
        int axis=chosen.axis(),u=(axis+1)%3,v=(axis+2)%3;
        double plane=chosen.plane();
        // For a full cube, a native witness at y=65 belongs to the y=64
        // outward-facing block when its outward normal is +Y. Retain actual
        // fractional slab faces instead of inventing a whole-block boundary.
        if(Math.abs(plane-Math.rint(plane))<=PLANE_EPS)plane=Math.rint(plane);
        double samplePlane=plane;
        int fixed=(int)Math.floor(samplePlane-chosen.sign()*GRID_EPS);
        Set<String> original=new HashSet<>();
        for(var target:verified) {
            if(!owner.equals(target.ownerKey()))continue;
            String[] parts=target.localBlock().split(",",-1);
            if(parts.length!=3)continue;
            try {
                int[] c={Integer.parseInt(parts[0]),Integer.parseInt(parts[1]),Integer.parseInt(parts[2])};
                if(c[axis]!=fixed || c[u]<chosen.minU() || c[u]>chosen.maxU()
                        || c[v]<chosen.minV() || c[v]>chosen.maxV())continue;
                original.add(target.localBlock());
            }catch(NumberFormatException bad){return new Result(List.of(),0,"BAD_VERIFIED_COORDINATE");}
        }
        if(original.isEmpty())return new Result(List.of(),0,"NO_VERIFIED_FACE_ANCHOR");

        var added=new ArrayList<GameImpactCredit.Target>();
        Set<String> accepted=new HashSet<>(original);
        int attempted=0;
        for(int a=chosen.minU();a<=chosen.maxU();a++)for(int b=chosen.minV();b<=chosen.maxV();b++) {
            int[] c=new int[3];c[axis]=fixed;c[u]=a;c[v]=b;
            String key=c[0]+","+c[1]+","+c[2];
            if(original.contains(key))continue;
            double[] p={c[0]+0.5,c[1]+0.5,c[2]+0.5};p[axis]=samplePlane;
            attempted++;
            var found=shape.apply(new BlockPos(c[0],c[1],c[2]),
                    new FrameMath.Vec(p[0],p[1],p[2]));
            if(found!=null && owner.equals(found.ownerKey()) && key.equals(found.localBlock())) {
                added.add(found);accepted.add(key);
            }
        }
        // Validate all four corner cells with actual owner-local collision
        // shapes. An unrelated diagonal contact cannot certify a broad face.
        int[][] corners={{chosen.minU(),chosen.minV()},{chosen.minU(),chosen.maxV()},
                {chosen.maxU(),chosen.minV()},{chosen.maxU(),chosen.maxV()}};
        for(var corner:corners) {
            int[] c=new int[3];c[axis]=fixed;c[u]=corner[0];c[v]=corner[1];
            if(!accepted.contains(c[0]+","+c[1]+","+c[2]))
                return new Result(List.of(),attempted,"CORNERS_SHAPE_UNVERIFIED");
        }
        return new Result(added,attempted,"SHAPE_VERIFIED_FACE");
    }
}
