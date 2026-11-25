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
    private static final double PENETRATION=1.0/32.0; // Actual shape-to-shape separation bound; NOT loosened.
    // Native impact witnesses can be noisy. Candidate acceptance is NOT damage authorization:
    // real opposing exterior shapes must pass positiveOverlap() before any F is assigned.
    private static final double WITNESS_TOLERANCE=1.0/16.0;
    private static final double FACE_OPPOSITION=.98;
    private static final double EPS=1.0e-5;
    private static final double MIN_AREA=1.0e-10;
    private static final int MAX_PAIRS=ContactCreditLedger.MAX_REGIONS;
    private static final int MAX_CANDIDATE_CELLS=64; // Existing transformed-face budget; not Native contact records
    private static final FrameMath.Vec ZERO=new FrameMath.Vec(0,0,0);
    private static final FrameMath.Pose WORLD=new FrameMath.Pose(ZERO,ZERO,new FrameMath.Vec(1,1,1),0,0,0,1);

    record Cell(GameImpactCredit.Target target,BlockPos allocated,List<AABB> boxes) {
        Cell { Objects.requireNonNull(target);Objects.requireNonNull(allocated);boxes=List.copyOf(boxes); }
    }
    interface ShapeReader {
        Cell get(int side,BlockPos allocated);
        /** An unknown neighbor is not evidence of empty space. */
        boolean exposed(int side,FrameMath.Vec point,MovePlan.SignedAxes inward);
        /** Distinguish an unknown cell caused by exhausted read budget from real air. */
        default boolean budgetExhausted(){return false;}
        /** A failed/unloaded cell read is NOT proof that the face terminates. */
        default boolean unresolvedCell(int side,BlockPos allocated){return false;}
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
            if(worldA.dot(worldB)>-.80)return null; // Candidate hint, not face proof.
            var aa=axis(rawA,tick);var bb=axis(rawB,tick);
            if(aa==null||bb==null)return null;
            // Effect consumer witness is only an anchor. Significant separation
            // or lateral drift cannot certify its surrounding block geometry.
            var d=b.toWorldPoint(localB).minus(a.toWorldPoint(localA));
            double along=d.dot(worldA);
            var lateral=d.minus(worldA.times(along));
            if(!d.finite()||Math.abs(along)>WITNESS_TOLERANCE||lateral.squareLength()>WITNESS_TOLERANCE*WITNESS_TOLERANCE)return null;
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
        if(Math.abs(component)<.75)return null; // Real face must still pass strict geometric overlap.
        int sign=component>0?-1:1; // Inward: positive means LOW face.
        return new MovePlan.SignedAxes(axis==0?sign:0,axis==1?sign:0,axis==2?sign:0,true,tick);
    }
    private static int axis(MovePlan.SignedAxes axes){return axes.x()!=0?0:axes.y()!=0?1:2;}

    /** A native record is an anchor, not a separate surface or F credit. The
     * same pair of rigid poses and oriented faces is scanned only once, even
     * when the solver reports many different anchors for that component. */
    static Result reconstruct(Frame frame,ShapeReader source,int maxChecks){
        return reconstructAll(frame==null?List.of():List.of(frame),source,maxChecks);
    }
    private record FrameKey(FrameMath.Pose a,FrameMath.Pose b,
                            int ax,int ay,int az,int bx,int by,int bz) {
        FrameKey(Frame f){this(f.a(),f.b(),f.inwardA().x(),f.inwardA().y(),f.inwardA().z(),
                f.inwardB().x(),f.inwardB().y(),f.inwardB().z());}
    }
    /** A completed connected contact component is independent of unrelated native
     * records and of the 100-record Sable telemetry cap. Incomplete components
     * never publish a partial F seed set. Previously completed components remain
     * certified if a later component exhausts its budget. */
    static Result reconstructAll(List<Frame> frames,ShapeReader source,int maxChecks){
        if(frames==null||frames.isEmpty()||source==null||maxChecks<1)
            return new Result(List.of(),0,false,"NO_FRAME");
        var groups=new LinkedHashMap<FrameKey,List<Frame>>();
        for(var f:frames)if(f!=null)groups.computeIfAbsent(new FrameKey(f),k->new ArrayList<>()).add(f);
        var completed=new LinkedHashSet<Match>();
        int checked=0;boolean incomplete=false;
        for(var group:groups.values()){
            if(checked>=maxChecks||completed.size()>=MAX_PAIRS||source.budgetExhausted()){
                incomplete=true;break;
            }
            var search=new Search(group.getFirst(),source,maxChecks-checked,MAX_PAIRS-completed.size());
            completed.addAll(search.run(group));
            checked+=search.checks;
            if(search.incomplete)incomplete=true;
            if(source.budgetExhausted())break;
        }
        return new Result(new ArrayList<>(completed),checked,incomplete,
                incomplete?(completed.isEmpty()?"SURFACE_BUDGET":"PARTIAL_CERTIFIED_COMPONENTS"):
                completed.isEmpty()?"NO_POSITIVE_FACE":"BILATERAL_SURFACE");
    }
    private static final class Search {
        final Frame frame;
        final ShapeReader source;
        final int maxChecks,maxPairs;
        final Map<Pair,Boolean> evaluated=new HashMap<>();
        final Map<Pair,Match> complete=new LinkedHashMap<>();
        final Map<Pair,Match> active=new LinkedHashMap<>();
        final Set<Pair> queued=new HashSet<>();
        final Map<BlockPos,Cell> cellsA=new HashMap<>(),cellsB=new HashMap<>();
        final ArrayDeque<Pair> frontier=new ArrayDeque<>();
        int checks;
        boolean incomplete;
        Search(Frame frame,ShapeReader source,int maxChecks,int maxPairs){
            this.frame=frame;this.source=source;this.maxChecks=maxChecks;this.maxPairs=maxPairs;
        }
        Cell cell(int side,BlockPos pos){
            var cache=side==0?cellsA:cellsB;
            if(!cache.containsKey(pos))cache.put(pos,source.get(side,pos));
            if(source.budgetExhausted()||cache.get(pos)==null&&source.unresolvedCell(side,pos))incomplete=true;
            return cache.get(pos);
        }
        boolean enqueue(Pair p){
            if(incomplete||complete.containsKey(p)||queued.contains(p))return false;
            Boolean pass=evaluated.get(p);
            if(pass==null){
                Cell a=cell(0,p.a()),b=cell(1,p.b());
                if(incomplete)return false;
                if(a==null||b==null){evaluated.put(p,Boolean.FALSE);return false;}
                if(checks>=maxChecks){incomplete=true;return false;}
                checks++;
                pass=positiveOverlap(frame,source,a,b);
                if(source.budgetExhausted()){incomplete=true;return false;}
                evaluated.put(p,pass);
            }
            if(pass){queued.add(p);frontier.add(p);}
            return pass;
        }
        List<Match> run(List<Frame> anchors){
            // Each anchor seeds its own connected component. Completed components
            // stay cached, so 100 witnesses on one face do NOT rescan it 100 times.
            for(var witness:anchors){
                if(incomplete)break;
                for(var pa:SableConsumerTargets.cells(witness.contactA())){
                    if(incomplete)break;
                    if(!anchorFace(witness,cell(0,pa),0))continue;
                    for(var pb:SableConsumerTargets.cells(witness.contactB())){
                        if(incomplete)break;
                        if(!anchorFace(witness,cell(1,pb),1))continue;
                        active.clear();queued.clear();frontier.clear();
                        if(!enqueue(new Pair(pa,pb)))continue;
                        drain();
                        if(incomplete){active.clear();frontier.clear();break;}
                        complete.putAll(active); // ONLY a closed, fully inspected component.
                    }
                }
            }
            return new ArrayList<>(complete.values());
        }
        private void drain(){
            while(!frontier.isEmpty()&&!incomplete){
                var current=frontier.remove();
                Cell a=cell(0,current.a()), b=cell(1,current.b());
                if(incomplete)break;
                if(a==null||b==null)continue;
                if(complete.size()+active.size()>=maxPairs){incomplete=true;break;}
                active.put(current,new Match(a.target(),b.target()));
                // A real tangent seam on EACH changed owner is required.
                for(int side=0;side<2&&!incomplete;side++){
                    BlockPos at=side==0?current.a():current.b();
                    Cell old=side==0?a:b;
                    int normal=axis(frame.inward(side));
                    int u=(normal+1)%3,v=(normal+2)%3;
                    for(int dir=0;dir<4&&!incomplete;dir++){
                        int[] xyz={at.getX(),at.getY(),at.getZ()};
                        xyz[dir<2?u:v]+=(dir%2==0?1:-1);
                        var next=cell(side,new BlockPos(xyz[0],xyz[1],xyz[2]));
                        if(incomplete)break;
                        if(next==null||!touchingFaceCells(frame,old,next,side))continue;
                        var candidates=opposingCandidateCells(frame,next,side);
                        if(candidates==null){incomplete=true;break;}
                        for(var other:candidates){
                            var p=side==0?new Pair(next.allocated(),other):new Pair(other,next.allocated());
                            if(!connectedTransition(current,p,a,b))continue;
                            enqueue(p);
                            if(incomplete)break;
                        }
                    }
                }
            }
        }
        private boolean connectedTransition(Pair current,Pair proposed,Cell a,Cell b){
            return (current.a().equals(proposed.a())
                    || touchingFaceCells(frame,a,cell(0,proposed.a()),0))
                    && (current.b().equals(proposed.b())
                    || touchingFaceCells(frame,b,cell(1,proposed.b()),1));
        }
    }
    /** Two adjacent block identities do not imply connected visible faces.
     * Partial collision boxes must touch across the actual tangent seam. */
    static boolean touchingFaceCells(Frame f,Cell old,Cell next){
        return touchingFaceCells(f,old,next,0);
    }
    static boolean touchingFaceCells(Frame f,Cell old,Cell next,int side){
        if(f==null||old==null||next==null)return false;
        int axis=axis(f.inward(side));
        int[] delta={next.allocated().getX()-old.allocated().getX(),
                next.allocated().getY()-old.allocated().getY(),
                next.allocated().getZ()-old.allocated().getZ()};
        int seam=-1;for(int i=0;i<3;i++)if(delta[i]!=0){
            if(seam!=-1||Math.abs(delta[i])!=1||i==axis)return false;
            seam=i;
        }
        if(seam<0)return false;
        int other=3-axis-seam;
        int inward=axis==0?f.inward(side).x():axis==1?f.inward(side).y():f.inward(side).z();
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
    private static List<BlockPos> opposingCandidateCells(Frame f,Cell cell,int side){
        if(cell==null)return List.of();
        int ax=axis(f.inward(side));var bounds=new double[]{Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
        for(var box:cell.boxes())for(var p:faceCorners(box,cell.allocated(),ax,f.inward(side))){
            var mapped=f.pose(1-side).toLocalPoint(f.pose(side).toWorldPoint(p));
            if(!mapped.finite())return null;
            double[] cs={mapped.x(),mapped.y(),mapped.z()};
            for(int i=0;i<3;i++){bounds[i]=Math.min(bounds[i],cs[i]);bounds[i+3]=Math.max(bounds[i+3],cs[i]);}
        }
        if(!Double.isFinite(bounds[0]))return List.of();
        var low=new int[3];var high=new int[3];long count=1;
        int opposingNormal=axis(f.inward(1-side));
        for(int i=0;i<3;i++){
            if(bounds[i]<Integer.MIN_VALUE+3||bounds[i+3]>Integer.MAX_VALUE-3)return null;
            if(i==opposingNormal){
                // A face lies on a cell boundary. Either side of that plane
                // can contain a real exterior B face; retain both cells.
                low[i]=(int)Math.floor(bounds[i]-EPS);
                high[i]=(int)Math.floor(bounds[i+3]+EPS);
            }else{
                // Tangential zero-area intersections (only a shared grid
                // edge) cannot receive F. Removing them here avoids the
                // expensive 3x3 candidate fan-out for every full cube.
                low[i]=(int)Math.floor(bounds[i]+EPS);
                high[i]=(int)Math.ceil(bounds[i+3]-EPS)-1;
                if(high[i]<low[i]){
                    // Very thin positive-width faces must still reach the
                    // authoritative polygon-area test, not silently vanish.
                    low[i]=(int)Math.floor(bounds[i]-EPS);
                    high[i]=(int)Math.floor(bounds[i+3]+EPS);
                }
            }
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
            if(n.dot(f.b().toWorldNormal(outwardB))>-FACE_OPPOSITION)return false;
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
                if(Math.abs(denom)<FACE_OPPOSITION)continue;
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
