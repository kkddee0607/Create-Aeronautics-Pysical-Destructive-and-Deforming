package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.diagnostics.RogTrace;
import com.physicaldestroydeform.diagnostics.RogTrace.Stage;
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
        default boolean takeUncertainExterior(){return false;}
        /** Distinguish an unknown cell caused by exhausted read budget from real air. */
        default boolean budgetExhausted(){return false;}
        /** A failed/unloaded cell read is NOT proof that the face terminates. */
        default boolean unresolvedCell(int side,BlockPos allocated){return false;}
    }
    record Match(GameImpactCredit.Target a,GameImpactCredit.Target b) {}
    record Result(List<Match> matches,int checks,boolean incomplete,String gate) {
        Result { matches=List.copyOf(matches); }
    }
    /** Observed relative normal displacement, not a fabricated contact pose.
     * Used only for planar, tangent-invariant motion across captured samples. */
    record Sweep(double minShift,double maxShift) {}
    record Frame(FrameMath.Pose a,FrameMath.Pose b,MovePlan.SignedAxes inwardA,
                 MovePlan.SignedAxes inwardB,FrameMath.Vec contactA,FrameMath.Vec contactB,Sweep sweep) {
        Frame(FrameMath.Pose a,FrameMath.Pose b,MovePlan.SignedAxes inwardA,
              MovePlan.SignedAxes inwardB,FrameMath.Vec contactA,FrameMath.Vec contactB){
            this(a,b,inwardA,inwardB,contactA,contactB,null);
        }
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
        return fromRecord(pair,record,tick,List.of());
    }
    static Frame fromRecord(GameImpactCredit.Pair pair,Contact record,long tick,List<CollisionFrame.Snapshot> pre) {
        if(pair==null||record==null){if(RogTrace.sample(Stage.R03,"RECORD_MISSING"))RogTrace.detail(Stage.R03,"RECORD_MISSING","pair={} recordPresent={}",pair,record!=null);return null;}
        if(record.serverTick()!=tick){if(RogTrace.sample(Stage.R03,"RECORD_TICK_MISMATCH"))RogTrace.detail(Stage.R03,"RECORD_TICK_MISMATCH","actual={} expected={}",record.serverTick(),tick);return null;}
        if(record.rawImpactScalar()<=0){if(RogTrace.sample(Stage.R03,"RECORD_NONPOSITIVE"))RogTrace.detail(Stage.R03,"RECORD_NONPOSITIVE","scalar={}",record.rawImpactScalar());return null;}
        if(!pair.dimension().equals(record.dimension())){if(RogTrace.sample(Stage.R03,"RECORD_DIMENSION_MISMATCH"))RogTrace.detail(Stage.R03,"RECORD_DIMENSION_MISMATCH","expected={} actual={}",pair.dimension(),record.dimension());return null;}
        if(!SableConsumerTargets.sourceRecordValid(record)){if(RogTrace.sample(Stage.R03,"RECORD_SOURCE_INVALID"))RogTrace.detail(Stage.R03,"RECORD_SOURCE_INVALID","runtimeA={} runtimeB={}",record.idA(),record.idB());return null;}
        try {
            int aSide=SableConsumerTargets.sourceSide(pair.a().stableKey(),record,pair.dimension());
            int bSide=SableConsumerTargets.sourceSide(pair.b().stableKey(),record,pair.dimension());
            if(aSide==0||bSide==0||aSide==bSide){if(RogTrace.sample(Stage.R03,"SIDE_MAPPING"))RogTrace.detail(Stage.R03,"SIDE_MAPPING","aSide={} bSide={} runtimeA={} runtimeB={}",aSide,bSide,record.idA(),record.idB());return null;}
            FrameMath.Pose a=pose(record,aSide==1),b=pose(record,bSide==1);
            if(!unit(a)||!unit(b)){if(RogTrace.sample(Stage.R03,"POSE_NOT_UNIT"))RogTrace.detail(Stage.R03,"POSE_NOT_UNIT","poseA={} poseB={}",a,b);return null;}
            var localA=SableConsumerTargets.candidate(record,aSide==1);
            var localB=SableConsumerTargets.candidate(record,bSide==1);
            if(localA==null||localB==null||!localA.finite()||!localB.finite()){if(RogTrace.sample(Stage.R03,"LOCAL_POINT_INVALID"))RogTrace.detail(Stage.R03,"LOCAL_POINT_INVALID","localA={} localB={}",localA,localB);return null;}
            var rawA=normal(record,aSide==1).unit();var rawB=normal(record,bSide==1).unit();
            var worldA=a.toWorldNormal(rawA);var worldB=b.toWorldNormal(rawB);
            double opposition=worldA.dot(worldB);
            if(opposition>-.80){if(RogTrace.sample(Stage.R03,"NATIVE_NORMAL_OPPOSITION"))RogTrace.detail(Stage.R03,"NATIVE_NORMAL_OPPOSITION","dot={} limit=-0.80 normalA={} normalB={}",opposition,worldA,worldB);return null;} // Candidate hint, not face proof.
            var aa=axis(rawA,tick);var bb=axis(rawB,tick);
            if(aa==null||bb==null){if(RogTrace.sample(Stage.R03,"DOMINANT_AXIS"))RogTrace.detail(Stage.R03,"DOMINANT_AXIS","localNormalA={} localNormalB={} min=0.75",rawA,rawB);return null;}
            // The two native witnesses are independent local anchors, not an
            // assertion that their effect-phase world positions coincide.
            var frame=new Frame(a,b,aa,bb,localA,localB);
            var sweep=observedSweep(frame,record,aSide==1,pre);
            if(RogTrace.sample(Stage.R03,"FRAME_ANCHOR"))RogTrace.detail(Stage.R03,"FRAME_ANCHOR",
                    "aSide={} bSide={} localA={} localB={} axisA={} axisB={} dot={} normalSweep={}",aSide,bSide,localA,localB,aa,bb,opposition,sweep);
            return new Frame(a,b,aa,bb,localA,localB,sweep);
        } catch(RuntimeException invalid){if(RogTrace.sample(Stage.R03,"FRAME_EXCEPTION"))RogTrace.detail(Stage.R03,"FRAME_EXCEPTION","type={}",invalid.getClass().getName());return null;}
    }
    private static FrameMath.Vec outward(MovePlan.SignedAxes axis){
        return new FrameMath.Vec(-axis.x(),-axis.y(),-axis.z());
    }
    private static FrameMath.Pose prePose(Contact c,boolean first,CollisionFrame.Snapshot snapshot){
        int id=first?c.idA():c.idB();if(id==-1)return WORLD;
        var expected=first?c.frameA():c.frameB();
        if(expected==null)return null;
        CollisionFrame.Body found=null;
        for(var body:snapshot.bodies())if(body.runtimeId()==id){if(found!=null)return null;found=body;}
        if(found==null||!found.handleValid()||!expected.uuid().equals(found.plotId())||!unit(found.pose())
                ||found.pose().rotationPoint().minus(expected.pose().rotationPoint()).squareLength()>1e-8)return null;
        return found.pose();
    }
    private static FrameMath.Vec relativePoint(FrameMath.Pose a,FrameMath.Pose b,FrameMath.Vec p){
        return a.toLocalPoint(b.toWorldPoint(p));
    }
    /** No general interpolation or pose correction. The tangent projection and
     * relative orientation must be invariant in every observed same-tick pose.
     * A measured change along the normal can bracket a planar contact. */
    private static Sweep observedSweep(Frame frame,Contact record,boolean first,List<CollisionFrame.Snapshot> pre){
        if(pre==null||pre.isEmpty())return null;
        var n=outward(frame.inwardA());
        var other=frame.a().toLocalNormal(frame.b().toWorldNormal(outward(frame.inwardB())));
        if(other.plus(n).squareLength()>EPS*EPS)return null;
        var baseline=relativePoint(frame.a(),frame.b(),frame.contactB());
        double min=0,max=0;int samples=0;
        var basis=List.of(new FrameMath.Vec(1,0,0),new FrameMath.Vec(0,1,0),new FrameMath.Vec(0,0,1));
        for(var snapshot:pre){
            if(snapshot==null||snapshot.phase()!=CollisionFrame.Phase.PRE_SOLVER
                    ||snapshot.serverTick()!=record.serverTick()||!record.dimension().equals(snapshot.dimension()))continue;
            if(!snapshot.containerAvailable()||record.runtimeStamp().known()&&!record.runtimeStamp().sameCapture(snapshot.runtimeStamp()))return null;
            var a=prePose(record,first,snapshot);var b=prePose(record,!first,snapshot);
            if(a==null||b==null)return null;
            for(var e:basis)if(a.toLocalVector(b.toWorldVector(e)).minus(
                    frame.a().toLocalVector(frame.b().toWorldVector(e))).squareLength()>EPS*EPS)return null;
            var delta=relativePoint(a,b,frame.contactB()).minus(baseline);
            double shift=delta.dot(n);
            if(!Double.isFinite(shift)||delta.minus(n.times(shift)).squareLength()>EPS*EPS)return null;
            min=Math.min(min,shift);max=Math.max(max,shift);samples++;
        }
        return samples>0&&max-min>EPS?new Sweep(min,max):null;
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

    private static double coordinate(FrameMath.Vec p,int axis){return axis==0?p.x():axis==1?p.y():p.z();}
    private static boolean onAnchorPlane(Frame f,List<FrameMath.Vec> face,int side){
        int ax=axis(f.inward(side));return Math.abs(coordinate(face.getFirst(),ax)-coordinate(f.contact(side),ax))<=PENETRATION;
    }
    /** A native record is an anchor, not a separate surface or F credit. The
     * same pair of rigid poses and oriented faces is scanned only once, even
     * when the solver reports many different anchors for that component. */
    static Result reconstruct(Frame frame,ShapeReader source,int maxChecks){
        return reconstructAll(frame==null?List.of():List.of(frame),source,maxChecks);
    }
    private record FrameKey(FrameMath.Pose a,FrameMath.Pose b,
                            int ax,int ay,int az,int bx,int by,int bz,Sweep sweep,double planeA,double planeB) {
        FrameKey(Frame f){this(f.a(),f.b(),f.inwardA().x(),f.inwardA().y(),f.inwardA().z(),
                f.inwardB().x(),f.inwardB().y(),f.inwardB().z(),f.sweep(),
                f.sweep()==null?0:coordinate(f.contactA(),axis(f.inwardA())),
                f.sweep()==null?0:coordinate(f.contactB(),axis(f.inwardB())));}
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
                incomplete=true;if(RogTrace.sample(Stage.R04,"SURFACE_GROUP_BUDGET"))RogTrace.detail(Stage.R04,"SURFACE_GROUP_BUDGET","checked={} maxChecks={} completedPairs={} maxPairs={}",checked,maxChecks,completed.size(),MAX_PAIRS);break;
            }
            var search=new Search(group.getFirst(),source,maxChecks-checked,MAX_PAIRS-completed.size());
            completed.addAll(search.run(group));
            checked+=search.checks;
            if(search.incomplete||search.anyIncomplete)incomplete=true;
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
        final Set<Pair> failedComponents=new HashSet<>();
        final Map<BlockPos,Cell> cellsA=new HashMap<>(),cellsB=new HashMap<>();
        final ArrayDeque<Pair> frontier=new ArrayDeque<>();
        int checks;
        boolean incomplete,anyIncomplete;
        Search(Frame frame,ShapeReader source,int maxChecks,int maxPairs){
            this.frame=frame;this.source=source;this.maxChecks=maxChecks;this.maxPairs=maxPairs;
        }
        Cell cell(int side,BlockPos pos){
            var cache=side==0?cellsA:cellsB;
            if(!cache.containsKey(pos))cache.put(pos,source.get(side,pos));
            if(source.budgetExhausted()||cache.get(pos)==null&&source.unresolvedCell(side,pos)){incomplete=true;if(RogTrace.sample(Stage.R04,"SHAPE_UNKNOWN_OR_BUDGET"))RogTrace.detail(Stage.R04,"SHAPE_UNKNOWN_OR_BUDGET","side={} allocated={} checks={} maxChecks={}",side,pos,checks,maxChecks);}
            return cache.get(pos);
        }
        boolean enqueue(Pair p){
            if(incomplete||complete.containsKey(p)||queued.contains(p))return false;
            if(failedComponents.contains(p)){incomplete=true;return false;}
            Boolean pass=evaluated.get(p);
            if(pass==null){
                Cell a=cell(0,p.a()),b=cell(1,p.b());
                if(incomplete)return false;
                if(a==null||b==null){evaluated.put(p,Boolean.FALSE);return false;}
                if(checks>=maxChecks){incomplete=true;if(RogTrace.sample(Stage.R04,"PAIR_CHECK_CAP"))RogTrace.detail(Stage.R04,"PAIR_CHECK_CAP","pair={} checked={} max={}",p,checks,maxChecks);return false;}
                checks++;
                pass=positiveOverlap(frame,source,a,b);
                if(source.takeUncertainExterior()||source.budgetExhausted()){incomplete=true;return false;}
                evaluated.put(p,pass);
            }
            if(pass){queued.add(p);frontier.add(p);}
            return pass;
        }
        List<Match> run(List<Frame> anchors){
            // Each anchor seeds its own connected component. Completed components
            // stay cached, so 100 witnesses on one face do NOT rescan it 100 times.
            for(var witness:anchors){
                if(incomplete){
                    anyIncomplete=true;
                    if(source.budgetExhausted()||checks>=maxChecks||complete.size()>=maxPairs)break;
                    // Discard only the unfinished traversal. Previously closed
                    // components remain immutable; unknown cells remain unknown.
                    incomplete=false;active.clear();queued.clear();frontier.clear();
                }
                for(var pa:SableConsumerTargets.cells(witness.contactA())){
                    if(incomplete)break;
                    if(!anchorFace(witness,cell(0,pa),0)){if(RogTrace.sample(Stage.R04,"ANCHOR_A_REJECTED"))RogTrace.detail(Stage.R04,"ANCHOR_A_REJECTED","allocated={} witness={} axis={}",pa,witness.contactA(),witness.inwardA());continue;}
                    for(var pb:SableConsumerTargets.cells(witness.contactB())){
                        if(incomplete)break;
                        if(!anchorFace(witness,cell(1,pb),1)){if(RogTrace.sample(Stage.R04,"ANCHOR_B_REJECTED"))RogTrace.detail(Stage.R04,"ANCHOR_B_REJECTED","allocated={} witness={} axis={}",pb,witness.contactB(),witness.inwardB());continue;}
                        active.clear();queued.clear();frontier.clear();
                        var anchor=new Pair(pa,pb);
                        if(failedComponents.contains(anchor)){anyIncomplete=true;continue;}
                        if(!enqueue(anchor))continue;
                        drain();
                        if(incomplete){if(RogTrace.sample(Stage.R04,"COMPONENT_DISCARDED"))RogTrace.detail(Stage.R04,"COMPONENT_DISCARDED","anchorA={} anchorB={} discardedPairs={} priorCertified={} checks={}",pa,pb,active.size(),complete.size(),checks);failedComponents.addAll(queued);failedComponents.add(anchor);active.clear();frontier.clear();break;}
                        if(RogTrace.sample(Stage.R04,"COMPONENT_CLOSED"))RogTrace.detail(Stage.R04,"COMPONENT_CLOSED","anchorA={} anchorB={} pairs={} checked={}",pa,pb,active.size(),checks);
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
                if(complete.size()+active.size()>=maxPairs){incomplete=true;if(RogTrace.sample(Stage.R04,"COMPONENT_PAIR_CAP"))RogTrace.detail(Stage.R04,"COMPONENT_PAIR_CAP","pairs={} max={}",complete.size()+active.size(),maxPairs);break;}
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
                        if(candidates==null){incomplete=true;if(RogTrace.sample(Stage.R04,"OPPOSING_CANDIDATE_CAP_OR_INVALID"))RogTrace.detail(Stage.R04,"OPPOSING_CANDIDATE_CAP_OR_INVALID","side={} allocated={} cap={}",side,next.allocated(),MAX_CANDIDATE_CELLS);break;}
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
                if(f.sweep()!=null){
                    double plane=coordinate(f.contact(1-side),i);
                    if(plane<Integer.MIN_VALUE+3||plane>Integer.MAX_VALUE-3)return null;
                    low[i]=(int)Math.floor(plane-PENETRATION);
                    high[i]=(int)Math.floor(plane+PENETRATION);
                }else{
                    low[i]=(int)Math.floor(bounds[i]-EPS);
                    high[i]=(int)Math.floor(bounds[i+3]+EPS);
                }
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
            double dot=n.dot(f.b().toWorldNormal(outwardB));
            if(dot>-FACE_OPPOSITION){if(RogTrace.sample(Stage.R04,"FACE_OPPOSITION"))RogTrace.detail(Stage.R04,"FACE_OPPOSITION","a={} b={} dot={} limit={}",a.allocated(),b.allocated(),dot,-FACE_OPPOSITION);return false;}
            int ax=axis(f.inwardA()),bx=axis(f.inwardB());
            for(var boxA:a.boxes())for(var boxB:b.boxes()){
                var fa=faceCorners(boxA,a.allocated(),ax,f.inwardA());
                var fb=faceCorners(boxB,b.allocated(),bx,f.inwardB());
                boolean swept=f.sweep()!=null&&onAnchorPlane(f,fa,0)&&onAnchorPlane(f,fb,1);
                var pa=fa.stream().map(f.a()::toWorldPoint).toList();
                var pb=fb.stream().map(f.b()::toWorldPoint).toList();
                var u=pa.get(1).minus(pa.get(0)).unit();var v=n.cross(u).unit();
                var areaA=project(pa,pa.getFirst(),u,v);
                var areaB=project(pb,pa.getFirst(),u,v);
                boolean close=true;
                for(var p:pb){
                    double depth=p.minus(pa.getFirst()).dot(n);
                    if(!contactDepth(f,depth,swept)){if(RogTrace.sample(Stage.R04,"FACE_GAP_OR_PENETRATION"))RogTrace.detail(Stage.R04,"FACE_GAP_OR_PENETRATION","a={} b={} depth={} maxGap={} minDepth={}",a.allocated(),b.allocated(),depth,EPS,-PENETRATION);close=false;break;}
                }
                if(!close)continue;
                var intersection=clip(ccw(areaB),ccw(areaA));
                double area=polygonArea(intersection);
                if(area<=MIN_AREA){if(RogTrace.sample(Stage.R04,"CLIPPED_AREA"))RogTrace.detail(Stage.R04,"CLIPPED_AREA","a={} b={} area={} minArea={}",a.allocated(),b.allocated(),area,MIN_AREA);continue;}
                double cx=0,cy=0;for(var p:intersection){cx+=p.x();cy+=p.y();}
                var centerA=pa.getFirst().plus(u.times(cx/intersection.size())).plus(v.times(cy/intersection.size()));
                // The two independently verified faces may have shallow
                // native penetration. Check each body's *own* exterior face,
                // not the A plane projected into the interior of body B.
                var nb=f.b().toWorldNormal(outwardB);
                double denom=nb.dot(n);
                if(Math.abs(denom)<FACE_OPPOSITION){if(RogTrace.sample(Stage.R04,"PROJECTION_OPPOSITION"))RogTrace.detail(Stage.R04,"PROJECTION_OPPOSITION","a={} b={} denom={} limit={}",a.allocated(),b.allocated(),denom,FACE_OPPOSITION);continue;}
                double distance=-nb.dot(centerA.minus(pb.getFirst()))/denom;
                if(!contactDepth(f,distance,swept)){if(RogTrace.sample(Stage.R04,"PROJECTED_DISTANCE"))RogTrace.detail(Stage.R04,"PROJECTED_DISTANCE","a={} b={} distance={} maxGap={} minDepth={}",a.allocated(),b.allocated(),distance,EPS,-PENETRATION);continue;}
                var centerB=centerA.plus(n.times(distance));
                var localA=f.a().toLocalPoint(centerA);
                var localB=f.b().toLocalPoint(centerB);
                if(source==null||(source.exposed(0,localA,f.inwardA())&&source.exposed(1,localB,f.inwardB()))){if(RogTrace.sample(Stage.R04,"FACE_PROVED"))RogTrace.detail(Stage.R04,"FACE_PROVED","a={} b={} area={} localA={} localB={} depth={}",a.allocated(),b.allocated(),area,localA,localB,distance);return true;}
                if(RogTrace.sample(Stage.R04,"EXTERIOR_REJECTED"))RogTrace.detail(Stage.R04,"EXTERIOR_REJECTED","a={} b={} localA={} localB={}",a.allocated(),b.allocated(),localA,localB);
            }
        }catch(RuntimeException invalid){if(RogTrace.sample(Stage.R04,"OVERLAP_EXCEPTION"))RogTrace.detail(Stage.R04,"OVERLAP_EXCEPTION","type={}",invalid.getClass().getName());return false;}
        return false;
    }
    private static boolean contactDepth(Frame f,double gap,boolean swept){
        if(!Double.isFinite(gap))return false;
        if(gap<=EPS&&gap>=-PENETRATION)return true;
        // All projected points must cross the actual contact plane in the
        // measured relative normal range. Persistent gaps stay rejected.
        return swept&&gap+f.sweep().minShift()<=EPS&&gap+f.sweep().maxShift()>=-EPS;
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
