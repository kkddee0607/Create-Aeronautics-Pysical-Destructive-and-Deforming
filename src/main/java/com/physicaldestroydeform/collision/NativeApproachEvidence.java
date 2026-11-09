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

    public static boolean approaching(GameImpactCredit.Pair pair,
                                      List<CollisionFrame.Snapshot> snapshots,
                                      NativeEffectDirection.Result normals,long tick) {
        Objects.requireNonNull(pair);
        if(snapshots==null||normals==null||normals.owners().size()!=2)return false;
        int observed=0;
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
                if(!Double.isFinite(closing)||closing<MIN_CLOSING_SPEED)return false;
                observed++;
            }catch(RuntimeException invalid){return false;}
        }
        return observed>0;
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
