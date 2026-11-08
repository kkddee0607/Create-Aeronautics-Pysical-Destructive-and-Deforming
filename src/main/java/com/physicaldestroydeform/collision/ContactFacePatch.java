package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import net.minecraft.core.BlockPos;

/**
 * Recover owner-local surface targets from each independently witnessed native
 * contact plane, not from four corners of a single axis-aligned rectangle.
 *
 * A contact witness bounds the reconstruction, but NEVER proves an interior
 * block: every added target is separately checked against its actual owner
 * collision shape. The post-effect source does not include a collider-pair
 * snapshot, so these targets remain a bounded GAME damage approximation and
 * must not be represented as new native force/solver contact records.
 */
final class ContactFacePatch {
    private ContactFacePatch() {}
    static final int MAX_AREA = ContactCreditLedger.MAX_TARGETS_PER_SIDE;
    private static final double PLANE_EPS = 0.125;
    private static final double NORMAL_DOT = 0.995;
    private static final double SURFACE_TOLERANCE = 0.51;
    // With only one distinct solver witness there is no evidenced polygon.
    // A one-cell local neighborhood, independently shape-checked, avoids a
    // corner-only game seed without extrapolating an arbitrary full face.
    private static final double SPARSE_HALO = 1.01;
    private static final double GRID_EPS = 1e-6;

    record Result(List<GameImpactCredit.Target> added, int attempted, String reason) {
        Result { added = List.copyOf(added); }
    }
    private record Point(double u, double v) {}
    private record Witness(double[] normal, double plane, Point point, int axis) {}
    private static final class Plane {
        final Witness first;
        final List<Point> points=new ArrayList<>();
        double planeSum;
        Plane(Witness first) {this.first=first;include(first);}
        void include(Witness witness) {points.add(witness.point());planeSum+=witness.plane();}
        double plane() {return planeSum/points.size();}
    }

    /** Shape reader receives a relative owner block and a point on its source-proven plane. */
    static Result reconstruct(String dimension, String owner, BlockPos gridOffset,
                              Collection<GameImpactCredit.Target> verified,
                              List<NativeCollisionTelemetry.Contact> records,
                              BiFunction<BlockPos,FrameMath.Vec,GameImpactCredit.Target> shape) {
        Objects.requireNonNull(dimension);Objects.requireNonNull(owner);
        Objects.requireNonNull(gridOffset);Objects.requireNonNull(verified);
        Objects.requireNonNull(records);Objects.requireNonNull(shape);
        if(records.isEmpty() || verified.isEmpty())
            return new Result(List.of(),0,"INSUFFICIENT_WITNESSES");

        var planes=new ArrayList<Plane>();
        boolean normalsMissing=false;
        for(var record:records) {
            if(!SableConsumerTargets.sourceRecordValid(record))continue;
            int side=SableConsumerTargets.sourceSide(owner,record,dimension);
            if(side==0)continue;
            var point=SableConsumerTargets.candidate(record,side==1);
            if(point==null || !point.finite())continue;
            double[] normal=side==1
                    ?new double[]{record.ax(),record.ay(),record.az()}
                    :new double[]{record.bx(),record.by(),record.bz()};
            double length=Math.sqrt(normal[0]*normal[0]+normal[1]*normal[1]+normal[2]*normal[2]);
            if(!Double.isFinite(length) || length<1e-9){normalsMissing=true;continue;}
            for(int i=0;i<3;i++)normal[i]/=length;
            int axis=0;
            for(int i=1;i<3;i++)if(Math.abs(normal[i])>Math.abs(normal[axis]))axis=i;
            double[] p={point.x()-gridOffset.getX(),point.y()-gridOffset.getY(),
                    point.z()-gridOffset.getZ()};
            int u=(axis+1)%3,v=(axis+2)%3;
            var witness=new Witness(normal,normal[0]*p[0]+normal[1]*p[1]+normal[2]*p[2],
                    new Point(p[u],p[v]),axis);
            Plane match=null;
            for(var group:planes) {
                if(group.first.axis()!=witness.axis())continue;
                double dot=0;
                for(int i=0;i<3;i++)dot+=group.first.normal()[i]*normal[i];
                if(dot>=NORMAL_DOT && Math.abs(group.plane()-witness.plane())<=PLANE_EPS) {
                    match=group;break;
                }
            }
            if(match==null)planes.add(new Plane(witness));
            else match.include(witness);
        }
        Set<String> original=new HashSet<>();
        for(var target:verified)if(owner.equals(target.ownerKey()))original.add(target.localBlock());
        Set<String> seen=new HashSet<>(original);
        var added=new ArrayList<GameImpactCredit.Target>();
        int attempted=0,faces=0,insufficient=0,limited=0,sparse=0;
        for(var group:planes) {
            var points=group.points;
            List<Point> hull=hull(points);
            if(hull.isEmpty()){insufficient++;continue;}
            boolean localHalo=hull.size()==1;
            double tolerance=localHalo?SPARSE_HALO:SURFACE_TOLERANCE;
            int axis=group.first.axis(),u=(axis+1)%3,v=(axis+2)%3;
            double[] normal=group.first.normal();
            double plane=group.plane();
            double minU=Double.POSITIVE_INFINITY,maxU=Double.NEGATIVE_INFINITY;
            double minV=Double.POSITIVE_INFINITY,maxV=Double.NEGATIVE_INFINITY;
            for(var p:hull) {
                minU=Math.min(minU,p.u());maxU=Math.max(maxU,p.u());
                minV=Math.min(minV,p.v());maxV=Math.max(maxV,p.v());
            }
            // A sparse solver witness can only authorize a one-cell halo,
            // NEVER a full synthetic plane. Every candidate still needs the
            // separately verified owner collision shape at the source plane.
            double lowU=Math.floor(minU-tolerance),highU=Math.ceil(maxU+tolerance)-1;
            double lowV=Math.floor(minV-tolerance),highV=Math.ceil(maxV+tolerance)-1;
            if(lowU<Integer.MIN_VALUE+2 || lowV<Integer.MIN_VALUE+2 ||
                    highU>Integer.MAX_VALUE-2 || highV>Integer.MAX_VALUE-2) {limited++;continue;}
            int u0=(int)lowU,u1=(int)highU,v0=(int)lowV,v1=(int)highV;
            long area=((long)u1-u0+1)*((long)v1-v0+1);
            if(area<1 || area>MAX_AREA || area>MAX_AREA-attempted) {limited++;continue;}

            // A witnessed face must have an existing independently verified
            // native contact target on its own plane, not just a nearby Plot.
            boolean anchored=false;
            for(var target:verified) {
                if(!owner.equals(target.ownerKey()))continue;
                int[] c=parse(target.localBlock());if(c==null)continue;
                double planeAxis=(plane-normal[u]*(c[u]+0.5)-normal[v]*(c[v]+0.5))/normal[axis];
                int blockAxis=(int)Math.floor(planeAxis-Math.signum(normal[axis])*GRID_EPS);
                if(c[axis]==blockAxis &&
                        (localHalo?segmentDistanceSquared(hull.getFirst(),hull.getFirst(),
                                c[u]+0.5,c[v]+0.5)<=tolerance*tolerance
                                :inside(hull,c[u]+0.5,c[v]+0.5))) {
                    anchored=true;break;
                }
            }
            if(!anchored){insufficient++;continue;}
            faces++;if(localHalo)sparse++;
            for(int a=u0;a<=u1;a++)for(int b=v0;b<=v1;b++) {
                double cu=a+0.5,cv=b+0.5;
                if(localHalo?segmentDistanceSquared(hull.getFirst(),hull.getFirst(),cu,cv)
                        >tolerance*tolerance:!inside(hull,cu,cv))continue;
                double along=(plane-normal[u]*cu-normal[v]*cv)/normal[axis];
                if(!Double.isFinite(along) || along<=Integer.MIN_VALUE+2 || along>=Integer.MAX_VALUE-2)
                    continue;
                int[] c=new int[3];c[u]=a;c[v]=b;
                c[axis]=(int)Math.floor(along-Math.signum(normal[axis])*GRID_EPS);
                String key=c[0]+","+c[1]+","+c[2];
                if(seen.contains(key))continue;
                double[] p=new double[3];p[u]=cu;p[v]=cv;p[axis]=along;
                attempted++;
                var found=shape.apply(new BlockPos(c[0],c[1],c[2]),
                        new FrameMath.Vec(p[0],p[1],p[2]));
                if(found!=null && owner.equals(found.ownerKey()) && key.equals(found.localBlock())) {
                    added.add(found);seen.add(key);
                }
            }
        }
        if(faces==0)return new Result(List.of(),attempted,
                limited>0?"PATCH_BUDGET":normalsMissing?"NO_VALID_NORMAL":"NO_PROVEN_SURFACE");
        return new Result(added,attempted,"SHAPE_VERIFIED_SURFACES faces="+faces+
                " sparseLocalHalos="+sparse+" limited="+limited+" unsupported="+insufficient);
    }

    private static int[] parse(String key) {
        String[] p=key.split(",",-1);if(p.length!=3)return null;
        try{return new int[]{Integer.parseInt(p[0]),Integer.parseInt(p[1]),Integer.parseInt(p[2])};}
        catch(NumberFormatException ignored){return null;}
    }
    private static double cross(Point a,Point b,Point c) {
        return (b.u()-a.u())*(c.v()-a.v())-(b.v()-a.v())*(c.u()-a.u());
    }
    private static List<Point> hull(List<Point> source) {
        var sorted=new ArrayList<>(source);
        sorted.sort(Comparator.comparingDouble(Point::u).thenComparingDouble(Point::v));
        var distinct=new ArrayList<Point>();
        for(var p:sorted)if(distinct.isEmpty() || !distinct.getLast().equals(p))distinct.add(p);
        if(distinct.size()<=2)return distinct;
        var lower=new ArrayList<Point>();
        for(var p:distinct){while(lower.size()>1&&cross(lower.get(lower.size()-2),lower.getLast(),p)<=0)
            lower.removeLast();lower.add(p);}
        var upper=new ArrayList<Point>();
        for(int i=distinct.size()-1;i>=0;i--){var p=distinct.get(i);
            while(upper.size()>1&&cross(upper.get(upper.size()-2),upper.getLast(),p)<=0)
                upper.removeLast();upper.add(p);}
        lower.removeLast();upper.removeLast();lower.addAll(upper);
        return lower;
    }
    private static double segmentDistanceSquared(Point a,Point b,double u,double v) {
        double du=b.u()-a.u(),dv=b.v()-a.v(),length=du*du+dv*dv;
        if(length==0)return (u-a.u())*(u-a.u())+(v-a.v())*(v-a.v());
        double t=Math.max(0,Math.min(1,((u-a.u())*du+(v-a.v())*dv)/length));
        double x=a.u()+t*du-u,y=a.v()+t*dv-v;
        return x*x+y*y;
    }
    private static boolean inside(List<Point> hull,double u,double v) {
        if(hull.size()<2)return false;
        double tol2=SURFACE_TOLERANCE*SURFACE_TOLERANCE;
        if(hull.size()==2)return segmentDistanceSquared(hull.get(0),hull.get(1),u,v)<=tol2;
        boolean inside=true;
        for(int i=0;i<hull.size();i++) {
            var a=hull.get(i);var b=hull.get((i+1)%hull.size());
            if(cross(a,b,new Point(u,v))<0)inside=false;
            if(segmentDistanceSquared(a,b,u,v)<=tol2)return true;
        }
        return inside;
    }
}
