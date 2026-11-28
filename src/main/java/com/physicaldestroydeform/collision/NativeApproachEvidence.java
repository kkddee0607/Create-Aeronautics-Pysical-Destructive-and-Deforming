package com.physicaldestroydeform.collision;

import java.util.List;
import java.util.Objects;
import static com.physicaldestroydeform.collision.FrameMath.Vec;

/** Conservative first-impact / regional reimpact proof from Sable pre-solver
 * linear body velocities and corroborated effect-phase opposing normals.
 * No contact point, solver impulse, or post-solver velocity is reconstructed.
 */
public final class NativeApproachEvidence {
    private static final double MIN_CLOSING_SPEED = 0.15;
    private NativeApproachEvidence() {}

    /** A previously resting contact must not mint an independent credit just
     * because a preceding solver substep imparted closing velocity. */
    public static boolean independentlyApproaching(GameImpactCredit.Pair pair,
            List<CollisionFrame.Snapshot> snapshots,NativeEffectDirection.Result normals,long tick) {
        if(snapshots==null)return false;
        CollisionFrame.Snapshot first=null;
        var epochs=new java.util.HashSet<Long>();
        for(var frame:snapshots) {
            if(frame==null||frame.phase()!=CollisionFrame.Phase.PRE_SOLVER
                    ||frame.serverTick()!=tick||!pair.dimension().equals(frame.dimension()))continue;
            if(frame.epoch()<0||!epochs.add(frame.epoch()))return false;
            if(first==null||frame.epoch()<first.epoch())first=frame;
        }
        return first!=null&&approaching(pair,List.of(first),normals,tick)
                &&approaching(pair,snapshots,normals,tick);
    }

    /** Earliest compatible PRE sample only. Later rebound does not negate
     * an already-proven earlier approach. Caller validates capture and poses. */
    static CollisionFrame.Snapshot earliest(GameImpactCredit.Pair pair,List<CollisionFrame.Snapshot> snapshots,long tick){
        if(snapshots==null)return null;
        CollisionFrame.Snapshot first=null;var epochs=new java.util.HashSet<Long>();
        for(var f:snapshots){
            if(f==null||f.phase()!=CollisionFrame.Phase.PRE_SOLVER||f.serverTick()!=tick||!pair.dimension().equals(f.dimension()))continue;
            if(!f.containerAvailable()||f.epoch()<0||!epochs.add(f.epoch()))return null;
            if(first==null||f.epoch()<first.epoch())first=f;
        }
        return first;
    }
    static double earliestClosing(GameImpactCredit.Pair pair,List<CollisionFrame.Snapshot> snapshots,
                                   NativeEffectDirection.Result normal,long tick){
        var f=earliest(pair,snapshots,tick);
        if(f==null||normal==null||normal.owners().size()!=2)return Double.NaN;
        var a=body(f,pair.a().stableKey());var b=body(f,pair.b().stableKey());
        if(a==INVALID||b==INVALID)return Double.NaN;
        try{
            Vec n=normal.forOwner(pair.a().stableKey()).inward();if(n==null)return Double.NaN;
            Vec world=a==null?n:a.pose().toWorldNormal(n);
            Vec va=a==null?new Vec(0,0,0):a.linearRaw(),vb=b==null?new Vec(0,0,0):b.linearRaw();
            double closing=-va.minus(vb).dot(world);
            return Double.isFinite(closing)?closing:Double.NaN;
        }catch(RuntimeException invalid){return Double.NaN;}
    }
    static boolean regionApproach(double closing){return Double.isFinite(closing)&&closing>=MIN_CLOSING_SPEED;}

    public static boolean approaching(GameImpactCredit.Pair pair,
                                      List<CollisionFrame.Snapshot> snapshots,
                                      NativeEffectDirection.Result normals,long tick) {
        Objects.requireNonNull(pair);
        if(snapshots==null||normals==null||normals.owners().size()!=2)return false;
        int observed=0;
        boolean closingObserved=false;
        for(var frame:snapshots) {
            if(frame==null||frame.phase()!=CollisionFrame.Phase.PRE_SOLVER
                    ||frame.serverTick()!=tick||!pair.dimension().equals(frame.dimension()))continue;
            if(!frame.containerAvailable())return false;
            var a=body(frame,pair.a().stableKey());
            var b=body(frame,pair.b().stableKey());
            if(a==INVALID||b==INVALID)return false;
            try {
                Vec n=normals.forOwner(pair.a().stableKey()).inward();
                if(n==null||!n.finite())return false;
                Vec worldNormal=a==null?n.unit():a.pose().toWorldNormal(n);
                Vec va=a==null?new Vec(0,0,0):a.linearRaw();
                Vec vb=b==null?new Vec(0,0,0):b.linearRaw();
                if(va==null||vb==null||!va.finite()||!vb.finite())return false;
                double closing=-(va.minus(vb)).dot(worldNormal);
                if(!Double.isFinite(closing) || closing<=-MIN_CLOSING_SPEED)return false;
                // Solver substeps may stop after impact: a subsequent stationary
                // pre-frame is not evidence that the earlier closing motion was false.
                if(closing>=MIN_CLOSING_SPEED)closingObserved=true;
                observed++;
            }catch(RuntimeException invalid){return false;}
        }
        return observed>0 && closingObserved;
    }
    // Distinguish the legitimate static World body (null) from invalid ownership.
    private static final CollisionFrame.Body INVALID=new CollisionFrame.Body(
            null,0,0,0,0,null,null,null,null,false);
    private static CollisionFrame.Body body(CollisionFrame.Snapshot frame,String owner) {
        if(owner.equals("WORLD:"+frame.dimension()))return null;
        if(!owner.startsWith("PLOT:"))return INVALID;
        CollisionFrame.Body found=null;
        for(var b:frame.bodies())if(("PLOT:"+b.plotId()).equals(owner)) {
            if(found!=null||!b.valid())return INVALID;
            found=b;
        }
        return found==null?INVALID:found;
    }
}
