package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
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
    private static final double PLANE_EPS = 0.45;
    private static final double AXIS_NORMAL_DOT = 0.80;
    private static final double OBLIQUE_NORMAL_DOT = 0.995;
    private static final double SURFACE_TOLERANCE = 0.51;
    // With only one distinct solver witness there is no evidenced polygon.
    // A one-cell local neighborhood, independently shape-checked, avoids a
    // corner-only game seed without extrapolating an arbitrary full face.
    private static final double SPARSE_HALO = 1.01;
    private static final double GRID_EPS = 1e-6;
    static final double PAIR_EPS = 1e-5;

    record Result(List<GameImpactCredit.Target> added, int attempted, String reason,
                  Set<GameImpactCredit.Target> planarInterior,
                  Map<GameImpactCredit.Target,FrameMath.Vec> interiorPoints,List<Footprint> footprints) {
        Result { added = List.copyOf(added); planarInterior=Set.copyOf(planarInterior);
            interiorPoints=Map.copyOf(interiorPoints);footprints=List.copyOf(footprints); }
        Result(List<GameImpactCredit.Target> added,int attempted,String reason){this(added,attempted,reason,Set.of(),Map.of(),List.of());}
        /** A second owner must independently prove a coplanar positive-area
         * polygon/shape intersection. Merely sharing a line or point is not enough. */
        boolean covers(FrameMath.Vec point){return point!=null&&point.finite()&&footprints.stream().anyMatch(f->f.covers(point));}
    }
    private record Point(double u, double v) {}
    record Footprint(int axis,double plane,List<Point> hull,
                     List<NativeCollisionTelemetry.Contact> witnesses){
        Footprint {hull=List.copyOf(hull);witnesses=List.copyOf(witnesses);}
        boolean covers(FrameMath.Vec p){
            if(p==null||!p.finite())return false;
            double[] v={p.x(),p.y(),p.z()};
            return Math.abs(v[axis]-plane)<=PAIR_EPS &&
                    overlapsCell(hull,Math.floor(v[(axis+1)%3]),Math.floor(v[(axis+2)%3]));
        }
    }
    private record Witness(double[] normal,double plane,Point point,int axis,boolean axisAligned) {}
    private static final class Plane {
        final Witness first;
        final List<Point> points=new ArrayList<>();
        final List<NativeCollisionTelemetry.Contact> records=new ArrayList<>();
        double planeSum;
        double minPlane=Double.POSITIVE_INFINITY,maxPlane=Double.NEGATIVE_INFINITY;
        Plane(Witness first,NativeCollisionTelemetry.Contact source) {this.first=first;include(first,source);}
        void include(Witness witness,NativeCollisionTelemetry.Contact source) {
            records.add(source);points.add(witness.point());planeSum+=witness.plane();
            minPlane=Math.min(minPlane,witness.plane());maxPlane=Math.max(maxPlane,witness.plane());
        }
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
            // Solver corner normals need not be identical even on one planar
            // voxel face. Compare the source-local dominant AXIS plane for
            // near-axis normals; retain exact oblique geometry separately.
            boolean axisAligned=Math.abs(normal[axis])>=0.90;
            double plane=axisAligned?p[axis]:normal[0]*p[0]+normal[1]*p[1]+normal[2]*p[2];
            var witness=new Witness(normal,plane,new Point(p[u],p[v]),axis,axisAligned);
            Plane match=null;
            for(var group:planes) {
                if(group.first.axis()!=witness.axis() || group.first.axisAligned()!=witness.axisAligned())continue;
                double dot=0;
                for(int i=0;i<3;i++)dot+=group.first.normal()[i]*normal[i];
                if(dot>=(witness.axisAligned()?AXIS_NORMAL_DOT:OBLIQUE_NORMAL_DOT)
                        && Math.abs(group.plane()-witness.plane())<=
                            (witness.axisAligned()?PLANE_EPS:0.125)) {
                    match=group;break;
                }
            }
            if(match==null)planes.add(new Plane(witness,record));
            else match.include(witness,record);
        }
        Set<String> original=new HashSet<>();
        for(var target:verified)if(owner.equals(target.ownerKey()))original.add(target.localBlock());
        Set<String> seen=new HashSet<>(original);
        var added=new ArrayList<GameImpactCredit.Target>();
        var planarInterior=new HashSet<GameImpactCredit.Target>();
        var interiorPoints=new HashMap<GameImpactCredit.Target,FrameMath.Vec>();
        var footprints=new ArrayList<Footprint>();
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
            // The rectangular envelope is NOT the verified face. A long thin
            // diagonal may span millions of envelope cells but only hundreds
            // of actual shape-proven cells. Bound rows and actual tested cells,
            // not the area of the enclosing rectangle.
            if(u1<u0 || v1<v0 || (long)u1-u0+1>MAX_AREA || attempted>=MAX_AREA) {
                limited++;continue;
            }

            // A witnessed face must have an existing independently verified
            // native contact target on its own plane, not just a nearby Plot.
            boolean anchored=false;
            for(var target:verified) {
                if(!owner.equals(target.ownerKey()))continue;
                int[] c=parse(target.localBlock());if(c==null)continue;
                double planeAxis=group.first.axisAligned()?plane
                        :(plane-normal[u]*(c[u]+0.5)-normal[v]*(c[v]+0.5))/normal[axis];
                int blockAxis=(int)Math.floor(planeAxis-Math.signum(normal[axis])*GRID_EPS);
                if(c[axis]==blockAxis &&
                        (localHalo?segmentDistanceSquared(hull.getFirst(),hull.getFirst(),
                                c[u]+0.5,c[v]+0.5)<=tolerance*tolerance
                                :inside(hull,c[u]+0.5,c[v]+0.5))) {
                    anchored=true;break;
                }
            }
            if(!anchored){insufficient++;continue;}
            int faceStart=added.size();
            boolean pairable=group.first.axisAligned()&&group.maxPlane-group.minPlane<=PAIR_EPS&&hull.size()>=3;
            boolean faceOverBudget=false;
            outer:for(int a=u0;a<=u1;a++) {
                // Convex hull edges intersected with one grid-column band.
                // This avoids testing an unverified enclosing rectangle.
                int[] vRange=rowCandidateRange(hull,a+0.5,v0,v1);
                for(int b=vRange[0];b<=vRange[1];b++) {
                    double cu=a+0.5,cv=b+0.5;
                    if(localHalo?segmentDistanceSquared(hull.getFirst(),hull.getFirst(),cu,cv)
                            >tolerance*tolerance:!inside(hull,cu,cv))continue;
                    double along=group.first.axisAligned()?plane
                            :(plane-normal[u]*cu-normal[v]*cv)/normal[axis];
                    if(!Double.isFinite(along) || along<=Integer.MIN_VALUE+2 || along>=Integer.MAX_VALUE-2)
                        continue;
                    int[] c=new int[3];c[u]=a;c[v]=b;
                    c[axis]=(int)Math.floor(along-Math.signum(normal[axis])*GRID_EPS);
                    String key=c[0]+","+c[1]+","+c[2];
                    if(seen.contains(key))continue;
                    if(attempted>=MAX_AREA){faceOverBudget=true;break outer;}
                    double[] p=new double[3];p[u]=cu;p[v]=cv;p[axis]=along;
                    attempted++;
                    var found=shape.apply(new BlockPos(c[0],c[1],c[2]),
                            new FrameMath.Vec(p[0],p[1],p[2]));
                    if(found!=null && owner.equals(found.ownerKey()) && key.equals(found.localBlock())) {
                        added.add(found);seen.add(key);
                        // A halo or line is a damage approximation, not evidence
                        // of an actual surface patch suitable for derived movement.
                        if(group.first.axisAligned()
                                && group.maxPlane-group.minPlane<=SableConsumerTargets.EPSILON
                                && strictlyWithinPolygon(hull,cu,cv))planarInterior.add(found);
                        // Pairing is authorized by positive AREA within the
                        // witnessed polygon, not by the location of a cell's
                        // center. Border blocks can touch along half a face.
                        if(pairable&&overlapsCell(hull,a,b))interiorPoints.put(found,new FrameMath.Vec(p[0],p[1],p[2]));
                    }
                }
            }
            if(faceOverBudget) {
                // An incomplete face MUST NOT appear to have been fully
                // recovered. Roll back its new identities, keep earlier
                // complete faces and report the work budget explicitly.
                for(int i=added.size()-1;i>=faceStart;i--){
                    var removed=added.remove(i);seen.remove(removed.localBlock());planarInterior.remove(removed);interiorPoints.remove(removed);
                }
                limited++;
            } else {
                faces++;
                if(localHalo)sparse++;
                if(pairable)footprints.add(new Footprint(axis,plane,hull,group.records));
            }
        }
        if(faces==0)return new Result(List.of(),attempted,
                limited>0?"PATCH_BUDGET":normalsMissing?"NO_VALID_NORMAL":"NO_PROVEN_SURFACE");
        return new Result(added,attempted,"SHAPE_VERIFIED_SURFACES faces="+faces+
                " sparseLocalHalos="+sparse+" limited="+limited+" unsupported="+insufficient,planarInterior,interiorPoints,footprints);
    }

    /** Conservative column-band bound: never removes a cell that could
     * satisfy the exact hull / distance test below. Broad margin is cheap;
     * the exact shape verifier remains authoritative. */
    private static int[] rowCandidateRange(List<Point> hull,double u,int minV,int maxV) {
        double low=Double.POSITIVE_INFINITY,high=Double.NEGATIVE_INFINITY;
        for(int i=0;i<hull.size();i++) {
            Point a=hull.get(i),b=hull.get((i+1)%hull.size());
            double left=Math.min(a.u(),b.u()),right=Math.max(a.u(),b.u());
            if(right<u-2 || left>u+2)continue;
            if(a.u()==b.u()) {
                low=Math.min(low,Math.min(a.v(),b.v()));
                high=Math.max(high,Math.max(a.v(),b.v()));
            } else {
                double t0=Math.max(0,Math.min(1,(u-2-a.u())/(b.u()-a.u())));
                double t1=Math.max(0,Math.min(1,(u+2-a.u())/(b.u()-a.u())));
                double v0=a.v()+t0*(b.v()-a.v());
                double v1=a.v()+t1*(b.v()-a.v());
                low=Math.min(low,Math.min(v0,v1));
                high=Math.max(high,Math.max(v0,v1));
            }
        }
        if(low==Double.POSITIVE_INFINITY)return new int[]{1,0};
        return new int[]{Math.max(minV,(int)Math.floor(low-2)),
                Math.min(maxV,(int)Math.ceil(high+2))};
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
    /** A shared cell is not a shared contact patch: intersect BOTH witnessed
     * polygons and both owner-local cells after the exact rigid mapping.
     * The returned point is strictly inside positive common area and must
     * still pass both independent live exterior CollisionShape audits. */
    static FrameMath.Vec commonSurfacePoint(Footprint from,Footprint to,
            FrameMath.Vec fromCenter,FrameMath.Vec toCenter,
            java.util.function.UnaryOperator<FrameMath.Vec> fromTo){
        if(from==null||to==null||fromCenter==null||toCenter==null||fromTo==null
                ||!fromCenter.finite()||!toCenter.finite()
                ||!from.covers(fromCenter)||!to.covers(toCenter))return null;
        try{
            double[] source={fromCenter.x(),fromCenter.y(),fromCenter.z()};
            int u=(from.axis()+1)%3,v=(from.axis()+2)%3;
            var polygon=clip(from.hull(),square(Math.floor(source[u]),Math.floor(source[v])));
            if(polygon.size()<3)return null;
            var translated=new ArrayList<Point>(polygon.size());
            int targetU=(to.axis()+1)%3,targetV=(to.axis()+2)%3;
            for(var p:polygon){
                double[] xyz=new double[3];xyz[from.axis()]=from.plane();xyz[u]=p.u();xyz[v]=p.v();
                var mapped=fromTo.apply(new FrameMath.Vec(xyz[0],xyz[1],xyz[2]));
                if(mapped==null||!mapped.finite())return null;
                double[] values={mapped.x(),mapped.y(),mapped.z()};
                if(Math.abs(values[to.axis()]-to.plane())>PAIR_EPS)return null;
                translated.add(new Point(values[targetU],values[targetV]));
            }
            translated= new ArrayList<>(clip(translated,to.hull()));
            double[] target={toCenter.x(),toCenter.y(),toCenter.z()};
            translated=new ArrayList<>(clip(translated,square(Math.floor(target[targetU]),Math.floor(target[targetV]))));
            if(translated.size()<3||area(translated)<=GRID_EPS*GRID_EPS)return null;
            // Mean of a convex polygon's vertices lies in its interior.
            double au=0,av=0;
            for(var p:translated){au+=p.u();av+=p.v();}
            double[] xyz=new double[3];xyz[to.axis()]=to.plane();
            xyz[targetU]=au/translated.size();xyz[targetV]=av/translated.size();
            var point=new FrameMath.Vec(xyz[0],xyz[1],xyz[2]);
            return point.finite()?point:null;
        }catch(RuntimeException invalid){return null;}
    }
    private static List<Point> square(double u,double v){
        return List.of(new Point(u,v),new Point(u+1,v),new Point(u+1,v+1),new Point(u,v+1));
    }
    /** Sutherland-Hodgman clipping; both convex polygons are CCW. */
    private static List<Point> clip(List<Point> source,List<Point> bound){
        var polygon=new ArrayList<>(source);
        if(polygon.size()<3)return List.of();
        for(int j=0;j<bound.size()&&!polygon.isEmpty();j++){
            var left=bound.get(j);var right=bound.get((j+1)%bound.size());
            var clipped=new ArrayList<Point>();
            var previous=polygon.getLast();double prevDist=cross(left,right,previous);
            for(var current:polygon){
                double nextDist=cross(left,right,current);
                boolean in=nextDist>=0,wasIn=prevDist>=0;
                if(in!=wasIn){
                    double t=prevDist/(prevDist-nextDist);
                    clipped.add(new Point(previous.u()+t*(current.u()-previous.u()),
                            previous.v()+t*(current.v()-previous.v())));
                }
                if(in)clipped.add(current);
                previous=current;prevDist=nextDist;
            }
            polygon=clipped;
        }
        return polygon;
    }
    private static double area(List<Point> polygon){
        Point o=polygon.getFirst();double twice=0;
        for(int i=1;i+1<polygon.size();i++){
            Point a=polygon.get(i),b=polygon.get(i+1);
            twice+=(a.u()-o.u())*(b.v()-o.v())-(a.v()-o.v())*(b.u()-o.u());
        }
        return Math.abs(twice)*.5;
    }

    /** Positive-area convex-polygon/unit-cell intersection using separating
     * axes. Point/edge contact is rejected; no rectangular hull inflation. */
    private static boolean overlapsCell(List<Point> hull,double cellU,double cellV){
        if(hull.size()<3||!Double.isFinite(cellU)||!Double.isFinite(cellV))return false;
        double minU=Double.POSITIVE_INFINITY,maxU=Double.NEGATIVE_INFINITY;
        double minV=Double.POSITIVE_INFINITY,maxV=Double.NEGATIVE_INFINITY;
        for(var p:hull){
            minU=Math.min(minU,p.u());maxU=Math.max(maxU,p.u());
            minV=Math.min(minV,p.v());maxV=Math.max(maxV,p.v());
        }
        if(maxU<=cellU+GRID_EPS||minU>=cellU+1-GRID_EPS||
                maxV<=cellV+GRID_EPS||minV>=cellV+1-GRID_EPS)return false;
        double midU=cellU+.5,midV=cellV+.5;
        for(int i=0;i<hull.size();i++){
            Point a=hull.get(i),b=hull.get((i+1)%hull.size());
            double nx=a.v()-b.v(),ny=b.u()-a.u();
            double low=Double.POSITIVE_INFINITY,high=Double.NEGATIVE_INFINITY;
            for(var p:hull){double projection=p.u()*nx+p.v()*ny;
                low=Math.min(low,projection);high=Math.max(high,projection);}
            double projected=midU*nx+midV*ny;
            double half=(Math.abs(nx)+Math.abs(ny))*.5;
            double eps=GRID_EPS*Math.max(1,Math.abs(nx)+Math.abs(ny));
            if(high<=projected-half+eps||low>=projected+half-eps)return false;
        }
        return true;
    }

    private static boolean strictlyWithinPolygon(List<Point> hull,double u,double v){
        if(hull.size()<3)return false;
        var p=new Point(u,v);
        for(int i=0;i<hull.size();i++)if(cross(hull.get(i),hull.get((i+1)%hull.size()),p)<=GRID_EPS)return false;
        return true;
    }
}
