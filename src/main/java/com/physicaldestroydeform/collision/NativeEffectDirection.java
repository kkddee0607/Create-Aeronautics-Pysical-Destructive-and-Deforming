package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import com.physicaldestroydeform.collision.FrameMath.Vec;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Sable Rapier 2.0.6 clearCollisions()[3..8] contains genuine opposing
 * local_n1/local_n2 MANIFOLD normals. These are NOT measured impulse vectors.
 * The static (-1) rigid collider uses the world basis. Plot normals are in
 * its local collider basis, independently corroborated against the Sable
 * post-effect owner pose. Distinct simultaneous contact normals are averaged
 * per owner in that owner's fixed local frame, after checking each pair's
 * opposing world normals. A degenerate average is UNKNOWN, never an axis guess.
 * Effect arrays have no per-record solver substep/point identity: this result
 * only authorizes directional F prediction, never a block move.
 */
public final class NativeEffectDirection {
    private NativeEffectDirection() {}
    private static final double MIN_OPPOSING_COS = .98;
    // A roughly symmetric multi-face impact has no defensible single inward
    // direction; leave damage non-directional rather than inventing one.
    private static final double MIN_MEAN_RESULTANT = .10;

    public record Result(Map<String, ForcePropagation.Orientation> owners,
                         String reason, int corroboratedRecords,
                         double meanResultant, double minimumAlignment) {
        public Result { owners = Map.copyOf(owners); }
        public Result(Map<String,ForcePropagation.Orientation> owners,String reason,int count) {
            this(owners,reason,count,0,0);
        }
        public ForcePropagation.Orientation forOwner(String owner) {
            return owners.getOrDefault(owner, ForcePropagation.Orientation.unknown());
        }
    }
    public static Result unknown(String reason) { return new Result(Map.of(),reason,0); }

    public static Result verify(GameImpactCredit.Pair pair,
                                List<NativeCollisionTelemetry.Contact> contacts) {
        if (pair == null || contacts == null || contacts.isEmpty()) return unknown("NO_NATIVE_WITNESSES");
        var candidates = new HashMap<String,Vec>();
        var witnesses = new HashMap<String,List<Vec>>();
        int inspected=0;
        for(var c:contacts) {
            if(c==null || !pair.dimension().equals(c.dimension()) || c.capSaturated())
                return unknown("BAD_OR_SATURATED_NATIVE_RECORD");
            String a=owner(c.idA(),c.frameA(),c.dimension());
            String b=owner(c.idB(),c.frameB(),c.dimension());
            if(a==null || b==null || a.equals(b)
                    || !(a.equals(pair.a().stableKey())&&b.equals(pair.b().stableKey())
                    || a.equals(pair.b().stableKey())&&b.equals(pair.a().stableKey())))
                return unknown("OWNER_OR_EFFECT_POSE_UNVERIFIED");
            try {
                Vec localA=new Vec(c.ax(),c.ay(),c.az()).unit();
                Vec localB=new Vec(c.bx(),c.by(),c.bz()).unit();
                Vec worldA=world(localA,c.idA(),c.frameA());
                Vec worldB=world(localB,c.idB(),c.frameB());
                if(worldA.dot(worldB)>-MIN_OPPOSING_COS)
                    return unknown("NATIVE_OPPOSING_NORMALS_DISAGREE");
                // The source normals are collider-local, not world normals.
                // Explicitly convert each inward WORLD direction back through
                // its OWN effect-pose inverse transpose. Translation is never
                // applied to a normal; non-uniform / signed scale matters.
                Vec inA=local(worldA.times(-1),c.idA(),c.frameA());
                Vec inB=local(worldB.times(-1),c.idB(),c.frameB());
                if(inA.dot(localA.times(-1))<0.999999
                        || inB.dot(localB.times(-1))<0.999999)
                    return unknown("NATIVE_NORMAL_FRAME_ROUNDTRIP_FAILED");
                witnesses.computeIfAbsent(a,k->new ArrayList<>()).add(inA);
                witnesses.computeIfAbsent(b,k->new ArrayList<>()).add(inB);
                inspected++;
            }catch(IllegalArgumentException invalid) {
                return unknown("NONFINITE_OR_INVALID_NATIVE_NORMAL");
            }
        }
        if(inspected==0 || witnesses.size()!=2)return unknown("INCOMPLETE_NATIVE_NORMALS");
        double minResultant=1.0,minAlignment=1.0;
        for(var entry:witnesses.entrySet()) {
            Vec sum=new Vec(0,0,0);
            for(var n:entry.getValue())sum=sum.plus(n);
            double length=Math.sqrt(sum.squareLength());
            double coherence=length/entry.getValue().size();
            if(!Double.isFinite(coherence) || coherence<MIN_MEAN_RESULTANT)
                return new Result(Map.of(),"AVERAGE_NORMAL_DEGENERATE",inspected,coherence,0);
            Vec mean=sum.unit();
            for(var n:entry.getValue())minAlignment=Math.min(minAlignment,mean.dot(n));
            minResultant=Math.min(minResultant,coherence);
            candidates.put(entry.getKey(),mean);
        }
        var owners=new HashMap<String,ForcePropagation.Orientation>();
        for(var entry:candidates.entrySet())
            owners.put(entry.getKey(),new ForcePropagation.Orientation(entry.getValue(),
                    "AVERAGED_SABLE_RAPIER_LOCAL_MANIFOLD_EFFECT_PHASE"));
        return new Result(owners,inspected>1?"AVERAGED_OPPOSING_NORMALS":"OPPOSING_NORMALS",
                inspected,minResultant,minAlignment);
    }
    /** Additional production gate: an effect pose must not be promoted to a
     * native world direction when any captured pre-solver substep had an
     * incompatible orientation. Collision records lack a substep index. */
    public static Result verify(GameImpactCredit.Pair pair,
                                List<NativeCollisionTelemetry.Contact> contacts,
                                List<CollisionFrame.Snapshot> preFrames) {
        Result result=verify(pair,contacts);
        if(result.owners().isEmpty())return result;
        if(preFrames==null || preFrames.isEmpty())return unknown("MISSING_PRE_SOLVER_POSE");
        for(var c:contacts) {
            if(c.idA()>=0 && !sameLinearFrameAcrossSubsteps(c.idA(),c.frameA(),c.dimension(),c.serverTick(),preFrames))
                return unknown("PLOT_NORMAL_LINEAR_FRAME_UNVERIFIED");
            if(c.idB()>=0 && !sameLinearFrameAcrossSubsteps(c.idB(),c.frameB(),c.dimension(),c.serverTick(),preFrames))
                return unknown("PLOT_NORMAL_LINEAR_FRAME_UNVERIFIED");
        }
        return result;
    }
    private static boolean sameLinearFrameAcrossSubsteps(int id,
                   NativeCollisionTelemetry.OwnerFrame frame,String dimension,long tick,
                   List<CollisionFrame.Snapshot> snapshots) {
        if(frame==null || frame.pose()==null || !frame.pose().valid())return false;
        int matching=0;
        var e=frame.pose();
        for(var s:snapshots) {
            if(s==null || s.phase()!=CollisionFrame.Phase.PRE_SOLVER
                    || s.serverTick()!=tick || !dimension.equals(s.dimension()))continue;
            CollisionFrame.Body match=null;
            for(var b:s.bodies())if(b.runtimeId()==id) {
                if(match!=null)return false;
                match=b;
            }
            if(match==null || !frame.uuid().equals(match.plotId())
                    || match.pose()==null || !match.pose().valid())return false;
            var p=match.pose();
            double dot=e.qx()*p.qx()+e.qy()*p.qy()+e.qz()*p.qz()+e.qw()*p.qw();
            double ea=e.qx()*e.qx()+e.qy()*e.qy()+e.qz()*e.qz()+e.qw()*e.qw();
            double pa=p.qx()*p.qx()+p.qy()*p.qy()+p.qz()*p.qz()+p.qw()*p.qw();
            if(!Double.isFinite(dot) || !Double.isFinite(ea) || !Double.isFinite(pa)
                    || dot*dot/(ea*pa)<.995*.995
                    || !sameScale(e.scale().x(),p.scale().x())
                    || !sameScale(e.scale().y(),p.scale().y())
                    || !sameScale(e.scale().z(),p.scale().z()))return false;
            matching++;
        }
        return matching>0;
    }
    private static boolean sameScale(double a,double b) {
        return Double.isFinite(a) && Double.isFinite(b)
                && Math.abs(a-b)<=1.0e-5*Math.max(1.0,Math.max(Math.abs(a),Math.abs(b)));
    }
    /** Optional signed-grid witnesses for the existing direction preview.
     * Diagonal native vectors remain usable for F propagation but MUST NOT
     * be rounded into a supposedly exact block-deformation axis. */
    public static Map<String,DirectionEvidence> gridAxes(Result result,long tick,long preEpoch) {
        if(result==null || result.owners().isEmpty() || tick<0 || preEpoch<0)return Map.of();
        var out=new HashMap<String,DirectionEvidence>();
        for(var entry:result.owners().entrySet()) {
            Vec n=entry.getValue().inward();
            if(n==null)continue;
            double[] a={n.x(),n.y(),n.z()};
            int axis=0;
            for(int i=1;i<3;i++)if(Math.abs(a[i])>Math.abs(a[axis]))axis=i;
            if(Math.abs(a[axis])<.98)continue;
            int[] signed={0,0,0};signed[axis]=a[axis]>0?1:-1;
            out.put(entry.getKey(),new DirectionEvidence(entry.getKey(),
                    "SABLE_RAPIER_LOCAL_MANIFOLD_NORMAL",
                    "AVERAGED_EFFECT_NORMAL_AND_PRE_POSE_CORROBORATED_SUBSTEP_UNKNOWN",
                    tick,-1,preEpoch,signed[0],signed[1],signed[2]));
        }
        return Map.copyOf(out);
    }
    private static String owner(int id,NativeCollisionTelemetry.OwnerFrame frame,String dimension) {
        if(id==-1)return "WORLD:"+dimension;
        if(id<0 || frame==null)return null;
        return "PLOT:"+frame.uuid();
    }
    private static Vec world(Vec local,int id,NativeCollisionTelemetry.OwnerFrame frame) {
        if(id==-1)return local; // Sable Rapier fixed ground collider is world-aligned.
        if(frame==null || frame.pose()==null)
            throw new IllegalArgumentException("missing Sable effect frame");
        return frame.pose().toWorldNormal(local);
    }
    private static Vec local(Vec worldNormal,int id,NativeCollisionTelemetry.OwnerFrame frame) {
        if(id==-1)return worldNormal.unit();
        if(frame==null || frame.pose()==null)
            throw new IllegalArgumentException("missing Sable effect frame");
        return frame.pose().toLocalNormal(worldNormal);
    }
}
