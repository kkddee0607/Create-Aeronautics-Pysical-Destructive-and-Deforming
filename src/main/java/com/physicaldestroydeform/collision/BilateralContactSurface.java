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
    private static final int MAX_FACE_PIECES=128, MAX_FACE_WORK=4096;
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
        /** Other owner-local cells that can cover this actual face. Null means unknown.
         * The cell itself is always checked for internal composite-shape occlusion. */
        default List<Cell> exteriorCells(int side,Cell cell,AABB face,MovePlan.SignedAxes inward){return List.of();}
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
    private record Rect(double u0,double v0,double u1,double v1) {}
    private enum Overlap { MATCH, NONE, UNKNOWN }
    private static final class FaceBudget {
        int remaining=MAX_FACE_WORK;
        boolean charge(){return remaining-->0;}
    }

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
        if(pre==null||pre.isEmpty())return sweepRejected("NO_PRE_CAPTURE",0);
        var n=outward(frame.inwardA());
        var other=frame.a().toLocalNormal(frame.b().toWorldNormal(outward(frame.inwardB())));
        if(other.plus(n).squareLength()>EPS*EPS)return sweepRejected("NORMAL_MISMATCH",other.plus(n).squareLength());
        var baseline=relativePoint(frame.a(),frame.b(),frame.contactB());
        double min=0,max=0;int samples=0;
        var basis=List.of(new FrameMath.Vec(1,0,0),new FrameMath.Vec(0,1,0),new FrameMath.Vec(0,0,1));
        for(var snapshot:pre){
            if(snapshot==null||snapshot.phase()!=CollisionFrame.Phase.PRE_SOLVER
                    ||snapshot.serverTick()!=record.serverTick()||!record.dimension().equals(snapshot.dimension()))continue;
            if(!snapshot.containerAvailable()||record.runtimeStamp().known()&&!record.runtimeStamp().sameCapture(snapshot.runtimeStamp()))return sweepRejected("CAPTURE_EPOCH_MISMATCH",samples);
            var a=prePose(record,first,snapshot);var b=prePose(record,!first,snapshot);
            if(a==null||b==null)return sweepRejected("BODY_POSE_MISSING",samples);
            for(var e:basis){
                double rotation=a.toLocalVector(b.toWorldVector(e)).minus(
                    frame.a().toLocalVector(frame.b().toWorldVector(e))).squareLength();
                if(rotation>EPS*EPS)return sweepRejected("ROTATION_CHANGED",rotation);
            }
            var delta=relativePoint(a,b,frame.contactB()).minus(baseline);
            double shift=delta.dot(n);
            if(!Double.isFinite(shift))return sweepRejected("INVALID_NUMERIC",shift);
            double tangent=delta.minus(n.times(shift)).squareLength();
            if(tangent>EPS*EPS)return sweepRejected("TANGENTIAL_MOTION",tangent);
            min=Math.min(min,shift);max=Math.max(max,shift);samples++;
        }
        if(samples==0)return sweepRejected("NO_MATCHING_PRE_CAPTURE",0);
        if(max-min<=EPS)return sweepRejected("ZERO_SWEEP",max-min);
        if(RogTrace.sample(Stage.R03,"SWEEP_VALID"))RogTrace.detail(Stage.R03,"SWEEP_VALID","samples={} minShift={} maxShift={} exactSubstep=false",samples,min,max);
        return new Sweep(min,max);
    }
    private static Sweep sweepRejected(String reason,double value){
        if(RogTrace.sample(Stage.R03,"SWEEP_"+reason))RogTrace.detail(Stage.R03,"SWEEP_"+reason,"value={} exactSubstep=false",value);
        return null;
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
        final Map<BlockPos,List<AABB>> exteriorA=new HashMap<>(),exteriorB=new HashMap<>();
        final ArrayDeque<Pair> frontier=new ArrayDeque<>();
        int checks;
        boolean incomplete,anyIncomplete;
        Search(Frame frame,ShapeReader source,int maxChecks,int maxPairs){
            this.frame=frame;this.source=source;this.maxChecks=maxChecks;this.maxPairs=maxPairs;
        }
        Cell anchorCell(int side,BlockPos pos){
            var cache=side==0?cellsA:cellsB;
            if(!cache.containsKey(pos))cache.put(pos,source.get(side,pos));
            if(source.budgetExhausted())incomplete=true;
            else if(cache.get(pos)==null&&source.unresolvedCell(side,pos))anyIncomplete=true;
            return cache.get(pos);
        }
        Cell cell(int side,BlockPos pos){
            var cache=side==0?cellsA:cellsB;
            if(!cache.containsKey(pos))cache.put(pos,source.get(side,pos));
            if(source.budgetExhausted()||cache.get(pos)==null&&source.unresolvedCell(side,pos)){incomplete=true;if(RogTrace.sample(Stage.R04,"SHAPE_UNKNOWN_OR_BUDGET"))RogTrace.detail(Stage.R04,"SHAPE_UNKNOWN_OR_BUDGET","side={} allocated={} checks={} maxChecks={}",side,pos,checks,maxChecks);}
            return cache.get(pos);
        }
        List<AABB> exterior(int side,Cell cell){
            var cache=side==0?exteriorA:exteriorB;
            if(!cache.containsKey(cell.allocated())){
                List<AABB> faces;
                try{faces=exteriorFaces(frame,source,cell,side,new FaceBudget());}
                catch(RuntimeException unsafe){faces=null;}
                cache.put(cell.allocated(),faces);
            }
            return cache.get(cell.allocated());
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
                var verdict=overlap(frame,source,a,b,exterior(0,a),exterior(1,b));
                boolean uncertain=source.takeUncertainExterior();
                if(verdict==Overlap.UNKNOWN||uncertain||source.budgetExhausted()){incomplete=true;return false;}
                pass=verdict==Overlap.MATCH;
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
                    if(!anchorFace(witness,anchorCell(0,pa),0)){if(RogTrace.sample(Stage.R04,"ANCHOR_A_REJECTED"))RogTrace.detail(Stage.R04,"ANCHOR_A_REJECTED","allocated={} witness={} axis={}",pa,witness.contactA(),witness.inwardA());continue;}
                    for(var pb:SableConsumerTargets.cells(witness.contactB())){
                        if(incomplete)break;
                        if(!anchorFace(witness,anchorCell(1,pb),1)){if(RogTrace.sample(Stage.R04,"ANCHOR_B_REJECTED"))RogTrace.detail(Stage.R04,"ANCHOR_B_REJECTED","allocated={} witness={} axis={}",pb,witness.contactB(),witness.inwardB());continue;}
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
        var depthAxis=f.pose(1-side).toLocalVector(f.a().toWorldNormal(outward(f.inwardA())));
        for(int i=0;i<3;i++){
            double margin=Math.abs(coordinate(depthAxis,i))*PENETRATION;
            bounds[i]-=margin;bounds[i+3]+=margin;
        }
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
                // Preserve the existing numerical seam tolerance; tilted
                // normal-depth displacement was already included above.
                low[i]=(int)Math.floor(bounds[i]+EPS);
                high[i]=(int)Math.ceil(bounds[i+3]-EPS)-1;
                if(high[i]<low[i]){
                    // Very thin positive-width faces must still reach the
                    // authoritative polygon-area test, not silently vanish.
                    low[i]=(int)Math.floor(bounds[i]-EPS);
                    high[i]=(int)Math.floor(bounds[i+3]+EPS);
                }
            }
            long span=(long)high[i]-low[i]+1;
            if(span<=0||span>MAX_CANDIDATE_CELLS||count>MAX_CANDIDATE_CELLS/span)return null;
            count*=span;
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
    static boolean positiveOverlap(Frame f,ShapeReader source,Cell a,Cell b){return overlap(f,source,a,b)==Overlap.MATCH;}
    private static double lo(AABB b,int a){return a==0?b.minX:a==1?b.minY:b.minZ;}
    private static double hi(AABB b,int a){return a==0?b.maxX:a==1?b.maxY:b.maxZ;}
    private static int coord(BlockPos p,int a){return a==0?p.getX():a==1?p.getY():p.getZ();}
    private static void addRect(List<Rect> out,double u0,double v0,double u1,double v1){
        if(u1>u0&&v1>v0)out.add(new Rect(u0,v0,u1,v1));
    }
    /** Disjoint rectangle subtraction in owner-local coordinates. Work/fragment
     * exhaustion is UNKNOWN, not an empty exterior. No synthetic surface is added. */
    private static List<AABB> exteriorFaces(Frame f,ShapeReader source,Cell cell,int side,FaceBudget budget){
        var out=new ArrayList<AABB>();int ax=axis(f.inward(side)),u=(ax+1)%3,v=(ax+2)%3;
        boolean low=coordinate(outward(f.inward(side)),ax)<0;
        for(var box:cell.boxes()){
            if(!budget.charge())return null;
            double plane=low?lo(box,ax):hi(box,ax);
            if(hi(box,u)<=lo(box,u)||hi(box,v)<=lo(box,v)||hi(box,ax)<=lo(box,ax))continue;
            var cover=source==null?List.<Cell>of():source.exteriorCells(side,cell,box,f.inward(side));
            if(cover==null)return null;
            var cells=new ArrayList<Cell>(cover.size()+1);cells.add(cell);cells.addAll(cover);
            List<Rect> pieces=List.of(new Rect(lo(box,u),lo(box,v),hi(box,u),hi(box,v)));
            for(var other:cells){
                if(other==null)return null;
                double da=(long)coord(other.allocated(),ax)-coord(cell.allocated(),ax);
                double du=(long)coord(other.allocated(),u)-coord(cell.allocated(),u);
                double dv=(long)coord(other.allocated(),v)-coord(cell.allocated(),v);
                for(var blocker:other.boxes()){
                    if(pieces.isEmpty())break;
                    if(!budget.charge())return null;
                    double a0=lo(blocker,ax)+da,a1=hi(blocker,ax)+da;
                    if(low?!(a0<plane&&a1>=plane):!(a1>plane&&a0<=plane))continue;
                    double u0=lo(blocker,u)+du,u1=hi(blocker,u)+du,v0=lo(blocker,v)+dv,v1=hi(blocker,v)+dv;
                    var next=new ArrayList<Rect>();
                    for(var r:pieces){
                        if(!budget.charge())return null;
                        double l=Math.max(r.u0(),u0),h=Math.min(r.u1(),u1),b=Math.max(r.v0(),v0),t=Math.min(r.v1(),v1);
                        if(l>=h||b>=t){next.add(r);continue;}
                        addRect(next,r.u0(),r.v0(),l,r.v1());addRect(next,h,r.v0(),r.u1(),r.v1());
                        addRect(next,l,r.v0(),h,b);addRect(next,l,t,h,r.v1());
                    }
                    if(next.size()>MAX_FACE_PIECES)return null;
                    pieces=next;
                }
                if(pieces.isEmpty())break;
            }
            for(var r:pieces){
                double[] l=new double[3],h=new double[3];l[ax]=h[ax]=plane;
                l[u]=r.u0();h[u]=r.u1();l[v]=r.v0();h[v]=r.v1();
                out.add(new AABB(l[0],l[1],l[2],h[0],h[1],h[2]));
                if(out.size()>MAX_FACE_PIECES)return null;
            }
        }
        return out;
    }
    /** Find cells immediately outside the face's true footprint. The caller
     * verifies owner and loaded state. No whole Plot scan or guessed air. */
    static List<BlockPos> exteriorCellPositions(Cell cell,AABB face,MovePlan.SignedAxes inward){
        int ax=axis(inward);boolean low=coordinate(outward(inward),ax)<0;
        double plane=coord(cell.allocated(),ax)+(low?lo(face,ax):hi(face,ax));
        double outside=Math.nextAfter(plane,low?Double.NEGATIVE_INFINITY:Double.POSITIVE_INFINITY);
        int[] min=new int[3],max=new int[3];long count=1;
        for(int i=0;i<3;i++){
            double l=i==ax?Math.floor(outside):Math.floor(coord(cell.allocated(),i)+lo(face,i));
            double h=i==ax?l:Math.ceil(coord(cell.allocated(),i)+hi(face,i))-1;
            if(!Double.isFinite(l)||!Double.isFinite(h)||l<Integer.MIN_VALUE+1||h>Integer.MAX_VALUE-1||h<l)return null;
            min[i]=(int)l;max[i]=(int)h;long span=(long)max[i]-min[i]+1;
            if(span>MAX_CANDIDATE_CELLS||count>MAX_CANDIDATE_CELLS/span)return null;count*=span;
        }
        var out=new ArrayList<BlockPos>();
        for(int x=min[0];x<=max[0];x++)for(int y=min[1];y<=max[1];y++)for(int z=min[2];z<=max[2];z++){
            var p=new BlockPos(x,y,z);if(!p.equals(cell.allocated()))out.add(p);
        }
        return out;
    }
    private static Overlap overlap(Frame f,ShapeReader source,Cell a,Cell b){
        if(f==null||a==null||b==null||a.boxes().isEmpty()||b.boxes().isEmpty())return Overlap.NONE;
        try{return overlap(f,source,a,b,exteriorFaces(f,source,a,0,new FaceBudget()),exteriorFaces(f,source,b,1,new FaceBudget()));}
        catch(RuntimeException invalid){return Overlap.UNKNOWN;}
    }
    private static Overlap overlap(Frame f,ShapeReader source,Cell a,Cell b,List<AABB> boxesA,List<AABB> boxesB){
        try{
            var n=f.a().toWorldNormal(outward(f.inwardA()));
            var nb=f.b().toWorldNormal(outward(f.inwardB()));
            double denom=n.dot(nb);
            if(denom>-FACE_OPPOSITION){if(RogTrace.sample(Stage.R04,"FACE_OPPOSITION"))RogTrace.detail(Stage.R04,"FACE_OPPOSITION","a={} b={} dot={} limit={}",a.allocated(),b.allocated(),denom,-FACE_OPPOSITION);return Overlap.NONE;}
            var budget=new FaceBudget();
            if(boxesA==null||boxesB==null){if(RogTrace.sample(Stage.R04,"EXTERIOR_UNKNOWN_OR_COMPLEXITY_CAP"))RogTrace.detail(Stage.R04,"EXTERIOR_UNKNOWN_OR_COMPLEXITY_CAP","a={} b={} perCellFaceWorkCap={}",a.allocated(),b.allocated(),MAX_FACE_WORK);return Overlap.UNKNOWN;}
            boolean parallel=nb.plus(n).squareLength()<=EPS*EPS;
            int ax=axis(f.inwardA()),bx=axis(f.inwardB());
            for(var boxA:boxesA)for(var boxB:boxesB){
                if(!budget.charge())return Overlap.UNKNOWN;
                var fa=faceCorners(boxA,a.allocated(),ax,f.inwardA());
                var fb=faceCorners(boxB,b.allocated(),bx,f.inwardB());
                boolean swept=f.sweep()!=null&&onAnchorPlane(f,fa,0)&&onAnchorPlane(f,fb,1);
                var pa=fa.stream().map(f.a()::toWorldPoint).toList();
                var pb=fb.stream().map(f.b()::toWorldPoint).toList();
                var origin=pa.getFirst();var u=pa.get(1).minus(origin).unit();var v=n.cross(u).unit();
                var intersection=clip(ccw(project(pb,origin,u,v)),ccw(project(pa,origin,u,v)));
                double before=polygonArea(intersection);
                if(before<=MIN_AREA){if(RogTrace.sample(Stage.R04,"CLIPPED_AREA"))RogTrace.detail(Stage.R04,"CLIPPED_AREA","a={} b={} area={} minArea={}",a.allocated(),b.allocated(),before,MIN_AREA);continue;}
                // Signed distance is affine on the common projection. Clip
                // the overlap, not irrelevant distant corners of the whole face.
                double lower=-PENETRATION,upper=parallel?EPS:0;
                if(swept){lower=Math.min(lower,-f.sweep().maxShift()-EPS);upper=Math.max(upper,-f.sweep().minShift()+EPS);}
                double low=lower,high=upper;
                java.util.function.ToDoubleFunction<Point> depth=p->-nb.dot(origin.plus(u.times(p.x())).plus(v.times(p.y())).minus(pb.getFirst()))/denom;
                var valid=clipHalfPlane(intersection,p->depth.applyAsDouble(p)-low);
                valid=clipHalfPlane(valid,p->high-depth.applyAsDouble(p));
                double area=polygonArea(valid);
                if(RogTrace.sample(Stage.R04,"FACE_DEPTH_CLIP"))RogTrace.detail(Stage.R04,"FACE_DEPTH_CLIP","a={} b={} projectedArea={} validArea={} minDepth={} maxDepth={} swept={} facePiecesA={} facePiecesB={}",a.allocated(),b.allocated(),before,area,low,high,swept,boxesA.size(),boxesB.size());
                if(area<=MIN_AREA){if(RogTrace.sample(Stage.R04,"FACE_GAP_OR_PENETRATION"))RogTrace.detail(Stage.R04,"FACE_GAP_OR_PENETRATION","a={} b={} projectedArea={} validArea={} minDepth={} maxGap={}",a.allocated(),b.allocated(),before,area,low,high);continue;}
                double cx=0,cy=0;for(var p:valid){cx+=p.x();cy+=p.y();}
                var center=new Point(cx/valid.size(),cy/valid.size());
                double distance=depth.applyAsDouble(center);
                if(!contactDepth(f,distance,swept))continue;
                var centerA=origin.plus(u.times(center.x())).plus(v.times(center.y()));
                var localA=f.a().toLocalPoint(centerA);var localB=f.b().toLocalPoint(centerA.plus(n.times(distance)));
                // Live reader also performs the existing independent point
                // check; synthetic geometry tests do not authorize mutations.
                if(source==null||(source.exposed(0,localA,f.inwardA())&&source.exposed(1,localB,f.inwardB()))){
                    if(RogTrace.sample(Stage.R04,"FACE_PROVED"))RogTrace.detail(Stage.R04,"FACE_PROVED","a={} b={} area={} localA={} localB={} depth={} swept={}",a.allocated(),b.allocated(),area,localA,localB,distance,swept);
                    return Overlap.MATCH;
                }
                if(RogTrace.sample(Stage.R04,"EXTERIOR_REJECTED"))RogTrace.detail(Stage.R04,"EXTERIOR_REJECTED","a={} b={} localA={} localB={}",a.allocated(),b.allocated(),localA,localB);
            }
        }catch(RuntimeException invalid){if(RogTrace.sample(Stage.R04,"OVERLAP_EXCEPTION"))RogTrace.detail(Stage.R04,"OVERLAP_EXCEPTION","type={}",invalid.getClass().getName());return Overlap.UNKNOWN;}
        return Overlap.NONE;
    }
    private static boolean contactDepth(Frame f,double gap,boolean swept){
        if(!Double.isFinite(gap))return false;
        if(gap<=EPS&&gap>=-PENETRATION)return true;
        return swept&&gap+f.sweep().minShift()<=EPS&&gap+f.sweep().maxShift()>=-EPS;
    }
    private static List<Point> clipHalfPlane(List<Point> input,java.util.function.ToDoubleFunction<Point> distance){
        if(input.isEmpty())return List.of();
        var out=new ArrayList<Point>();Point last=input.getLast();double prev=distance.applyAsDouble(last);
        for(var curr:input){double d=distance.applyAsDouble(curr);boolean inside=d>=0,prior=prev>=0;
            if(inside!=prior){double t=prev/(prev-d);if(Double.isFinite(t))out.add(new Point(last.x()+t*(curr.x()-last.x()),last.y()+t*(curr.y()-last.y())));}
            if(inside)out.add(curr);last=curr;prev=d;
        }
        return out;
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
            for(var curr:out){double d=cross(a,b,curr);boolean inside=d>=0,prior=prev>=0;
                if(inside!=prior){double t=prev/(prev-d);
                    if(Double.isFinite(t))next.add(new Point(last.x()+t*(curr.x()-last.x()),last.y()+t*(curr.y()-last.y())));
                }
                if(inside)next.add(curr);last=curr;prev=d;
            }out=next;
        }return out;
    }
}
