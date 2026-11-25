package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.deform.MovePlan;
import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry.Contact;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import java.util.*;

/**
 * Read-only GAME damage surface audit. A native witness anchors a connected
 * set of REAL opposing collision faces; it does not itself describe an area.
 * No new native manifold, impulse, F/Q credit, movement direction or cross-owner
 * propagation edge is asserted here. All resources are event-local and bounded.
 */
final class BilateralContactSurface {
    private BilateralContactSurface() {}
    private static final double PENETRATION=1.0/32.0; // Existing verified-contact bound.
    private static final double EPS=1.0e-5;
    private static final double MIN_AREA=1.0e-10;
    private static final int MAX_PAIRS=ContactCreditLedger.MAX_REGIONS;
    private static final int MAX_CANDIDATE_CELLS=64;
    private static final FrameMath.Vec ZERO=new FrameMath.Vec(0,0,0);
    private static final FrameMath.Pose WORLD=new FrameMath.Pose(ZERO,ZERO,new FrameMath.Vec(1,1,1),0,0,0,1);

    record Cell(GameImpactCredit.Target target,BlockPos allocated,List<AABB> boxes) {
        Cell { Objects.requireNonNull(target);Objects.requireNonNull(allocated);boxes=List.copyOf(boxes); }
    }
    interface ShapeReader {
        Cell get(int side,BlockPos allocated);
        /** An unknown neighbor is not evidence of empty space. */
        boolean exposed(int side,FrameMath.Vec point,MovePlan.SignedAxes inward);
    }
    record Match(GameImpactCredit.Target a,GameImpactCredit.Target b) {}
    record Result(List<Match> matches,int checks,boolean incomplete,String gate) {
        Result { matches=List.copyOf(matches); }
    }
    record Frame(FrameMath.Pose a,FrameMath.Pose b,MovePlan.SignedAxes inwardA,
                 MovePlan.SignedAxes inwardB,FrameMath.Vec contactA,FrameMath.Vec contactB) {
        Frame {
            Objects.requireNonNull(a);Objects.requireNonNull(b);Objects.requireNonNull(inwardA);
            Objects.requireNonNull(inwardB);Objects.requireNonNull(contactA);Objects.requireNonNull(contactB);
        }
        FrameMath.Pose pose(int side){return side==0?a:b;}
        MovePlan.SignedAxes inward(int side){return side==0?inwardA:inwardB;}
        FrameMath.Vec contact(int side){return side==0?contactA:contactB;}
    }
    private record Pair(BlockPos a,BlockPos b) {}
    private record Point(double x,double y) {}

    /** Each *single* native record carries both effect-phase poses. Never mix
     * its transform with a different contact's pose or penetration delta. */
    static Frame fromRecord(GameImpactCredit.Pair pair,Contact record,long tick) {
        if(pair==null||record==null||record.serverTick()!=tick||record.rawImpactScalar()<=0
                ||!pair.dimension().equals(record.dimension())||!SableConsumerTargets.sourceRecordValid(record))return null;
        try {
            int aSide=SableConsumerTargets.sourceSide(pair.a().stableKey(),record,pair.dimension());
            int bSide=SableConsumerTargets.sourceSide(pair.b().stableKey(),record,pair.dimension());
            if(aSide==0||bSide==0||aSide==bSide)return null;
            FrameMath.Pose a=pose(record,aSide==1),b=pose(record,bSide==1);
            if(!unit(a)||!unit(b))return null;
            var localA=SableConsumerTargets.candidate(record,aSide==1);
            var localB=SableConsumerTargets.candidate(record,bSide==1);
            if(localA==null||localB==null||!localA.finite()||!localB.finite())return null;
            var rawA=normal(record,aSide==1).unit();var rawB=normal(record,bSide==1).unit();
            var worldA=a.toWorldNormal(rawA);var worldB=b.toWorldNormal(rawB);
            if(worldA.dot(worldB)>-.90)return null;
            var aa=axis(rawA,tick);var bb=axis(rawB,tick);
            if(aa==null||bb==null)return null;
            // Effect consumer witness is only an anchor. Significant separation
            // or lateral drift cannot certify its surrounding block geometry.
            var d=b.toWorldPoint(localB).minus(a.toWorldPoint(localA));
            double along=d.dot(worldA);
            var lateral=d.minus(worldA.times(along));
            if(!d.finite()||along>EPS||along<-PENETRATION||lateral.squareLength()>PENETRATION*PENETRATION)return null;
            return new Frame(a,b,aa,bb,localA,localB);
        } catch(RuntimeException invalid){return null;}
    }
    private static FrameMath.Pose pose(Contact c,boolean first){
        if((first?c.idA():c.idB())==-1)return WORLD;
        var owner=first?c.frameA():c.frameB();
        return owner==null?null:owner.pose();
    }
    private static FrameMath.Vec normal(Contact c,boolean first){
        return first?new FrameMath.Vec(c.ax(),c.ay(),c.az()):new FrameMath.Vec(c.bx(),c.by(),c.bz());
    }
    private static boolean unit(FrameMath.Pose pose){
        return pose!=null&&pose.valid()&&Math.abs(pose.scale().x()-1)<EPS
                &&Math.abs(pose.scale().y()-1)<EPS&&Math.abs(pose.scale().z()-1)<EPS;
    }
    private static MovePlan.SignedAxes axis(FrameMath.Vec n,long tick){
        int axis=Math.abs(n.x())>=Math.abs(n.y())&&Math.abs(n.x())>=Math.abs(n.z())?0:
                Math.abs(n.y())>=Math.abs(n.z())?1:2;
        double component=axis==0?n.x():axis==1?n.y():n.z();
        if(Math.abs(component)<.90)return null;
        int sign=component>0?-1:1; // Inward: positive means LOW face.
        return new MovePlan.SignedAxes(axis==0?sign:0,axis==1?sign:0,axis==2?sign:0,true,tick);
    }
    private static int axis(MovePlan.SignedAxes axes){return axes.x()!=0?0:axes.y()!=0?1:2;}

    /** Flood fill *paired* and connected cells; a hole or an unsupported shape
     * stops traversal. Each pair must have positive overlap and opposing faces.
     * No O(sizeA*sizeB) scan and no unloaded-chunk generation. */
    static Result reconstruct(Frame frame,ShapeReader source,int maxChecks){
        if(frame==null||source==null||maxChecks<1)return new Result(List.of(),0,false,"NO_FRAME");
        var initialA=SableConsumerTargets.cells(frame.contactA());
        var initialB=SableConsumerTargets.cells(frame.contactB());
        var queue=new ArrayDeque<Pair>();var queued=new HashSet<Pair>();
        var certified=new LinkedHashMap<Match,Match>();
        int checks=0;boolean limited=false;
        for(var ap:initialA)for(var bp:initialB){
            if(checks>=maxChecks){limited=true;break;}
            checks++;
            Cell a=source.get(0,ap),b=source.get(1,bp);
            if(anchorFace(frame,a,0)&&anchorFace(frame,b,1)&&positiveOverlap(frame,source,a,b)){
                var key=new Pair(ap,bp);if(queued.add(key))queue.add(key);
            }
        }
        int tangent=axis(frame.inwardA());
        while(!queue.isEmpty()&&!limited) {
            Pair key=queue.remove();
            if(checks>=maxChecks||certified.size()>=MAX_PAIRS){limited=true;break;}
            checks++;
            Cell a=source.get(0,key.a()),b=source.get(1,key.b());
            if(!positiveOverlap(frame,source,a,b))continue;
            certified.putIfAbsent(new Match(a.target(),b.target()),new Match(a.target(),b.target()));
            // Advance to the four owner-local tangent neighbors. Any crossing
            // of an air gap is impossible; the corresponding opposite cells
            // are found by transform, NOT by a parallel-grid assumption.
            for(int dir=0;dir<4;dir++){
                if(checks>=maxChecks){limited=true;break;}
                int[] xyz={key.a().getX(),key.a().getY(),key.a().getZ()};
                int u=(tangent+1)%3,v=(tangent+2)%3;
                xyz[dir<2?u:v]+=(dir%2==0?1:-1);
                var neighbor=new BlockPos(xyz[0],xyz[1],xyz[2]);
                Cell next=source.get(0,neighbor);
                if(next==null||!touchingFaceCells(frame,a,next))continue;
                var other=opposingCandidateCells(frame,next);
                if(other==null){limited=true;break;}
                for(var candidate:other){
                    if(checks>=maxChecks){limited=true;break;}
                    var proposal=new Pair(neighbor,candidate);
                    if(queued.contains(proposal))continue;
                    checks++;
                    if(positiveOverlap(frame,source,next,source.get(1,candidate))){
                        queued.add(proposal);queue.add(proposal);
                    }
                }
            }
        }
        // Incomplete search may not masquerade as a complete reconstructed
        // face. Keep earlier Native-direct targets in the caller unchanged.
        return new Result(limited?List.of():new ArrayList<>(certified.values()),checks,limited,
                limited?"SURFACE_BUDGET":certified.isEmpty()?"NO_POSITIVE_FACE":"BILATERAL_SURFACE");
    }
    /** Two adjacent block identities do not imply connected visible faces.
     * Partial collision boxes must touch across the actual tangent seam. */
    static boolean touchingFaceCells(Frame f,Cell old,Cell next){
        if(f==null||old==null||next==null)return false;
        int axis=axis(f.inwardA());
        int[] delta={next.allocated().getX()-old.allocated().getX(),
                next.allocated().getY()-old.allocated().getY(),
                next.allocated().getZ()-old.allocated().getZ()};
        int seam=-1;for(int i=0;i<3;i++)if(delta[i]!=0){
            if(seam!=-1||Math.abs(delta[i])!=1||i==axis)return false;
            seam=i;
        }
        if(seam<0)return false;
        int other=3-axis-seam;
        int inward=axis==0?f.inwardA().x():axis==1?f.inwardA().y():f.inwardA().z();
        for(var boxA:old.boxes())for(var boxB:next.boxes()){
            double[] alo={boxA.minX,boxA.minY,boxA.minZ};
            double[] ahi={boxA.maxX,boxA.maxY,boxA.maxZ};
            double[] blo={boxB.minX,boxB.minY,boxB.minZ};
            double[] bhi={boxB.maxX,boxB.maxY,boxB.maxZ};
            int[] ac={old.allocated().getX(),old.allocated().getY(),old.allocated().getZ()};
            int[] bc={next.allocated().getX(),next.allocated().getY(),next.allocated().getZ()};
            double af=(inward>0?alo[axis]:ahi[axis])+ac[axis];
            double bf=(inward>0?blo[axis]:bhi[axis])+bc[axis];
            if(Math.abs(af-bf)>EPS)continue;
            double edge=delta[seam]>0?ahi[seam]+ac[seam]:alo[seam]+ac[seam];
            double edgeB=delta[seam]>0?blo[seam]+bc[seam]:bhi[seam]+bc[seam];
            if(Math.abs(edge-edgeB)>EPS)continue;
            double lo=Math.max(alo[other]+ac[other],blo[other]+bc[other]);
            double hi=Math.min(ahi[other]+ac[other],bhi[other]+bc[other]);
            if(hi-lo>EPS)return true;
        }
        return false;
    }
    private static List<BlockPos> opposingCandidateCells(Frame f,Cell cell){
        if(cell==null)return List.of();
        int ax=axis(f.inwardA());var bounds=new double[]{Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
        for(var box:cell.boxes())for(var p:faceCorners(box,cell.allocated(),ax,f.inwardA())){
            var mapped=f.b().toLocalPoint(f.a().toWorldPoint(p));
            if(!mapped.finite())return null;
            double[] cs={mapped.x(),mapped.y(),mapped.z()};
            for(int i=0;i<3;i++){bounds[i]=Math.min(bounds[i],cs[i]);bounds[i+3]=Math.max(bounds[i+3],cs[i]);}
        }
        if(!Double.isFinite(bounds[0]))return List.of();
        var low=new int[3];var high=new int[3];long count=1;
        for(int i=0;i<3;i++){
            if(bounds[i]<Integer.MIN_VALUE+3||bounds[i+3]>Integer.MAX_VALUE-3)return null;
            low[i]=(int)Math.floor(bounds[i]-EPS);high[i]=(int)Math.floor(bounds[i+3]+EPS);
            count*=high[i]-low[i]+1L;
            if(count>MAX_CANDIDATE_CELLS)return null;
        }
        var out=new ArrayList<BlockPos>();
        for(int x=low[0];x<=high[0];x++)for(int y=low[1];y<=high[1];y++)for(int z=low[2];z<=high[2];z++)
            out.add(new BlockPos(x,y,z));
        return out;
    }
    private static List<FrameMath.Vec> faceCorners(AABB box,BlockPos pos,int axis,MovePlan.SignedAxes inward){
        double[] low={box.minX,box.minY,box.minZ},high={box.maxX,box.maxY,box.maxZ};
        int sign=axis==0?inward.x():axis==1?inward.y():inward.z();
        double face=sign>0?low[axis]:high[axis];
        int u=(axis+1)%3,v=(axis+2)%3;
        var pts=new ArrayList<FrameMath.Vec>(4);
        for(int[] uv:new int[][]{{0,0},{1,0},{1,1},{0,1}}){
            double[] p=new double[3];p[axis]=face;
            p[u]=uv[0]==0?low[u]:high[u];p[v]=uv[1]==0?low[v]:high[v];
            pts.add(new FrameMath.Vec(p[0]+pos.getX(),p[1]+pos.getY(),p[2]+pos.getZ()));
        }
        return pts;
    }
    private static boolean anchorFace(Frame f,Cell cell,int side){
        if(cell==null)return false;
        var point=f.contact(side);
        var axis=axis(f.inward(side));
        int sign=axis==0?f.inward(side).x():axis==1?f.inward(side).y():f.inward(side).z();
        double[] xyz={point.x()-cell.allocated().getX(),point.y()-cell.allocated().getY(),point.z()-cell.allocated().getZ()};
        for(var box:cell.boxes()){
            double[] lo={box.minX,box.minY,box.minZ},hi={box.maxX,box.maxY,box.maxZ};
            double face=sign>0?lo[axis]:hi[axis];
            if(Math.abs(xyz[axis]-face)>PENETRATION)continue;
            boolean inside=true;
            for(int i=0;i<3;i++)if(i!=axis&&(xyz[i]<lo[i]-PENETRATION||xyz[i]>hi[i]+PENETRATION))inside=false;
            if(inside)return true;
        }return false;
    }
    static boolean positiveOverlap(Frame f,ShapeReader source,Cell a,Cell b){
        if(f==null||a==null||b==null||a.boxes().isEmpty()||b.boxes().isEmpty())return false;
        var outwardA=new FrameMath.Vec(-f.inwardA().x(),-f.inwardA().y(),-f.inwardA().z());
        var outwardB=new FrameMath.Vec(-f.inwardB().x(),-f.inwardB().y(),-f.inwardB().z());
        try{
            var n=f.a().toWorldNormal(outwardA);
            if(n.dot(f.b().toWorldNormal(outwardB))>-.995)return false;
            int ax=axis(f.inwardA()),bx=axis(f.inwardB());
            for(var boxA:a.boxes())for(var boxB:b.boxes()){
                var pa=faceCorners(boxA,a.allocated(),ax,f.inwardA()).stream().map(f.a()::toWorldPoint).toList();
                var pb=faceCorners(boxB,b.allocated(),bx,f.inwardB()).stream().map(f.b()::toWorldPoint).toList();
                var u=pa.get(1).minus(pa.get(0)).unit();var v=n.cross(u).unit();
                var areaA=project(pa,pa.getFirst(),u,v);
                var areaB=project(pb,pa.getFirst(),u,v);
                boolean close=true;
                for(var p:pb){
                    double depth=p.minus(pa.getFirst()).dot(n);
                    if(depth>EPS||depth<-PENETRATION){close=false;break;}
                }
                if(!close)continue;
                var intersection=clip(ccw(areaB),ccw(areaA));
                if(polygonArea(intersection)<=MIN_AREA)continue;
                double cx=0,cy=0;for(var p:intersection){cx+=p.x();cy+=p.y();}
                var centerA=pa.getFirst().plus(u.times(cx/intersection.size())).plus(v.times(cy/intersection.size()));
                // The two independently verified faces may have shallow
                // native penetration. Check each body's *own* exterior face,
                // not the A plane projected into the interior of body B.
                var nb=f.b().toWorldNormal(outwardB);
                double denom=nb.dot(n);
                if(Math.abs(denom)<.995)continue;
                double distance=-nb.dot(centerA.minus(pb.getFirst()))/denom;
                if(distance>EPS||distance<-PENETRATION)continue;
                var centerB=centerA.plus(n.times(distance));
                var localA=f.a().toLocalPoint(centerA);
                var localB=f.b().toLocalPoint(centerB);
                if(source==null||(source.exposed(0,localA,f.inwardA())&&source.exposed(1,localB,f.inwardB())))return true;
            }
        }catch(RuntimeException invalid){return false;}
        return false;
    }
    /** Geometry-only hook for pure algorithm tests. Live pairing also requires
     * a separate read-only exterior audit of neighboring blocks. */
    static boolean positiveOverlap(Frame f,Cell a,Cell b){return positiveOverlap(f,null,a,b);}
    private static List<Point> project(List<FrameMath.Vec> world,FrameMath.Vec o,FrameMath.Vec u,FrameMath.Vec v){
        var out=new ArrayList<Point>(world.size());
        for(var p:world){var d=p.minus(o);out.add(new Point(d.dot(u),d.dot(v)));}return out;
    }
    private static List<Point> ccw(List<Point> polygon){
        if(signedArea(polygon)>=0)return polygon;
        var back=new ArrayList<>(polygon);Collections.reverse(back);return back;
    }
    private static double signedArea(List<Point> p){
        double a=0;for(int i=0;i<p.size();i++){
            Point x=p.get(i),y=p.get((i+1)%p.size());a+=x.x()*y.y()-y.x()*x.y();
        }return a*.5;
    }
    private static double polygonArea(List<Point> p){return p.size()<3?0:Math.abs(signedArea(p));}
    private static double cross(Point a,Point b,Point p){return (b.x()-a.x())*(p.y()-a.y())-(b.y()-a.y())*(p.x()-a.x());}
    private static List<Point> clip(List<Point> subject,List<Point> boundary){
        var out=new ArrayList<>(subject);
        for(int i=0;i<boundary.size()&&!out.isEmpty();i++){
            Point a=boundary.get(i),b=boundary.get((i+1)%boundary.size());
            var next=new ArrayList<Point>();Point last=out.getLast();double prev=cross(a,b,last);
            for(var curr:out){double d=cross(a,b,curr);boolean inside=d>=-EPS,prior=prev>=-EPS;
                if(inside!=prior){double t=prev/(prev-d);
                    if(Double.isFinite(t))next.add(new Point(last.x()+t*(curr.x()-last.x()),last.y()+t*(curr.y()-last.y())));
                }
                if(inside)next.add(curr);last=curr;prev=d;
            }out=next;
        }return out;
    }
}
