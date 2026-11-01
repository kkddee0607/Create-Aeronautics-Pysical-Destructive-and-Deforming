package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry.OwnerFrame;
import com.physicaldestroydeform.collision.FrameMath.Vec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;

/**
 * 0.3.0 superFix: reproduce the ORIGINAL RapierPhysicsPipeline.processCollisionEffects()
 * block lookups (javap from Sable's nested Rapier 2.0.6 JAR).
 *
 * Sable uses BlockPos.set(localPoint + logicalPose.rotationPoint) for plots,
 * and BlockPos.set(localPoint) for the static side. It does NOT treat Plot
 * positions as overworld coordinates or apply a generic world quaternion to
 * these voxel lookups. This is Sable-consumer-equivalent geometry, NOT a raw
 * solver manifold/impulse or a general proof of collider pose.
 */
public final class SableConsumerTargets {
    private SableConsumerTargets() {}
    public static final int MAX_CANDIDATES=8;
    public static final double EPSILON=1.0/32.0;
    public enum Reason { VERIFIED, NO_NATIVE_FRAME, OWNER_MISMATCH, NO_SAMPLES,
        INVALID_COORDINATE, CONTACT_FRAME_UNCORROBORATED, CHUNK_UNAVAILABLE, NO_SOLID_BLOCK, MULTIPLE_BLOCKS, TOO_MANY_CANDIDATES }
    public record Result(GameImpactCredit.Target target, Reason reason, int checked, String stage) {
        public boolean verified(){return target!=null && reason==Reason.VERIFIED;}
    }
    public static Vec candidate(NativeCollisionTelemetry.Contact c,boolean sideA) {
        Objects.requireNonNull(c);
        OwnerFrame frame=sideA?c.frameA():c.frameB();
        Vec raw=sideA?new Vec(c.contactAX(),c.contactAY(),c.contactAZ())
                     :new Vec(c.contactBX(),c.contactBY(),c.contactBZ());
        if(!raw.finite())return null;
        if((sideA?c.idA():c.idB())==-1)return raw;
        if(frame==null)return null;
        return raw.plus(frame.pose().rotationPoint());
    }
    /** Pure and bounded: at most eight cells on a voxel boundary, never search radius. */
    public static List<BlockPos> cells(Vec point) {
        if(point==null || !point.finite() ||
                point.x()<=Integer.MIN_VALUE+2 || point.y()<=Integer.MIN_VALUE+2 || point.z()<=Integer.MIN_VALUE+2 ||
                point.x()>=Integer.MAX_VALUE-2 || point.y()>=Integer.MAX_VALUE-2 || point.z()>=Integer.MAX_VALUE-2)
            return List.of();
        int[][] axes={axis(point.x()),axis(point.y()),axis(point.z())};
        var result=new ArrayList<BlockPos>(8);
        for(int x:axes[0])for(int y:axes[1])for(int z:axes[2])
            result.add(new BlockPos(x,y,z));
        return List.copyOf(result);
    }
    private static int[] axis(double value) {
        int cell=(int)Math.floor(value);
        double frac=value-cell;
        if(frac<EPSILON)return new int[]{cell,cell-1};
        if(frac>1-EPSILON)return new int[]{cell,cell+1};
        return new int[]{cell};
    }
    /** Raw source ordinal is neither a manifold ID nor proof of distinct hits. */
    public static Result resolve(MinecraftServer server,String dim,String stableOwner,
                                 List<NativeCollisionTelemetry.Contact> raw) {
        if(raw==null || raw.isEmpty())return new Result(null,Reason.NO_SAMPLES,0,"R2");
        var positions=new LinkedHashSet<BlockPos>();
        var points=new ArrayList<Vec>();
        boolean sawMissingFrame=false;
        for(var c:raw) {
            boolean a=matches(stableOwner,c.idA(),c.frameA(),dim);
            boolean b=matches(stableOwner,c.idB(),c.frameB(),dim);
            if(a==b) return new Result(null,Reason.OWNER_MISMATCH,positions.size(),"R2");
            // The source-consumer voxel convention is not, by itself, a
            // calibrated collider-local transform. At minimum both independent
            // same-record Sable effect points must agree in world space before
            // this provisional game target can be credited (§21.1/21.3).
            if(!corroboratedContact(c))
                return new Result(null,Reason.CONTACT_FRAME_UNCORROBORATED,
                        positions.size(),"R2_CONTACT_FRAME");
            Vec p=candidate(c,a);
            if(p==null) {sawMissingFrame=true;continue;}
            // For the static sentinel, Sable's particle path uses raw local
            // coordinates as world coordinates but does NOT fetch a static
            // BlockState. Require the paired plot contact to corroborate the
            // actual world frame before a static voxel may be VERIFIED.
            if((a?c.idA():c.idB())==-1) {
                Vec otherWorld=worldPoint(c,!a);
                if(otherWorld==null || otherWorld.minus(p).squareLength()>0.25*0.25)
                    return new Result(null,Reason.INVALID_COORDINATE,positions.size(),"R2_STATIC_FRAME_UNCORROBORATED");
            }
            points.add(p);
            var cells=cells(p);
            if(cells.isEmpty())return new Result(null,Reason.INVALID_COORDINATE,positions.size(),"R2");
            positions.addAll(cells);
            if(positions.size()>MAX_CANDIDATES)
                return new Result(null,Reason.TOO_MANY_CANDIDATES,positions.size(),"R2");
        }
        if(sawMissingFrame) return new Result(null,Reason.NO_NATIVE_FRAME,positions.size(),"R2");
        var targets=new LinkedHashSet<GameImpactCredit.Target>();
        for(var pos:positions) {
            OwnerResolver.Check ch=null;
            for(var point:points) {
                var current=OwnerResolver.auditNative(server,dim,stableOwner,pos,point);
                if(current.verified()){ch=current;break;}
            }
            if(ch!=null && ch.verified()) {
                var k=ch.key();
                targets.add(new GameImpactCredit.Target(stableOwner,k.x()+","+k.y()+","+k.z(),k.fingerprint()));
            }
        }
        if(targets.isEmpty())return new Result(null,Reason.NO_SOLID_BLOCK,positions.size(),"R2");
        if(targets.size()!=1)return new Result(null,Reason.MULTIPLE_BLOCKS,positions.size(),"R2");
        return new Result(targets.iterator().next(),Reason.VERIFIED,positions.size(),"R2_CORROBORATED_GAME_PROXY");
    }
    /** R3: independent cross-side projection using Sable's exact effect-phase
     * logical pose. This is only an owner-local voxel candidate, never a
     * solver impulse or an asserted manifold frame. */
    public static Result crossProject(MinecraftServer server,String dim,String targetOwner,
                                      List<NativeCollisionTelemetry.Contact> raw) {
        if(raw==null || raw.isEmpty())return new Result(null,Reason.NO_SAMPLES,0,"R3");
        var positions=new LinkedHashSet<BlockPos>();
        var projectedPoints=new ArrayList<Vec>();
        for(var c:raw) {
            boolean targetA=matches(targetOwner,c.idA(),c.frameA(),dim);
            boolean targetB=matches(targetOwner,c.idB(),c.frameB(),dim);
            if(targetA==targetB)return new Result(null,Reason.OWNER_MISMATCH,positions.size(),"R3");
            // The independent other-side local point is mapped to the world
            // using precisely the same pose used by Sable for hit particles.
            Vec other=worldPoint(c,!targetA);
            if(other==null)return new Result(null,Reason.NO_NATIVE_FRAME,positions.size(),"R3");
            var targetFrame=targetA?c.frameA():c.frameB();
            int id=targetA?c.idA():c.idB();
            Vec ownerLocal;
            try {ownerLocal=id==-1?other:targetFrame==null?null:targetFrame.pose().toLocalPoint(other);}
            catch(RuntimeException invalid) {ownerLocal=null;}
            if(ownerLocal==null)return new Result(null,Reason.NO_NATIVE_FRAME,positions.size(),"R3");
            // Cross-projection must agree with native's own independent
            // same-record world point. A large disagreement forbids R3.
            Vec nativeTarget=worldPoint(c,targetA);
            if(nativeTarget==null || other.minus(nativeTarget).squareLength()>0.25*0.25)
                return new Result(null,Reason.INVALID_COORDINATE,positions.size(),"R3");
            projectedPoints.add(ownerLocal);
            positions.addAll(cells(ownerLocal));
            if(positions.size()>MAX_CANDIDATES)
                return new Result(null,Reason.TOO_MANY_CANDIDATES,positions.size(),"R3");
        }
        var valid=new LinkedHashSet<GameImpactCredit.Target>();
        for(var pos:positions) {
            OwnerResolver.Check check=null;
            for(var point:projectedPoints) {
                var current=OwnerResolver.auditNative(server,dim,targetOwner,pos,point);
                if(current.verified()){check=current;break;}
            }
            if(check!=null && check.verified()) {
                var key=check.key();
                valid.add(new GameImpactCredit.Target(targetOwner,
                        key.x()+","+key.y()+","+key.z(),key.fingerprint()));
            }
        }
        if(valid.isEmpty())return new Result(null,Reason.NO_SOLID_BLOCK,positions.size(),"R3");
        if(valid.size()!=1)return new Result(null,Reason.MULTIPLE_BLOCKS,positions.size(),"R3");
        return new Result(valid.iterator().next(),Reason.VERIFIED,positions.size(),"R3_CROSS_SIDE");
    }
    /** Same native record, two independent contact sides, effect-consumer phase.
     * This is a necessary cross-check, not an exact solver collider-pose proof. */
    static boolean corroboratedContact(NativeCollisionTelemetry.Contact c) {
        if(c==null)return false;
        Vec a=worldPoint(c,true), b=worldPoint(c,false);
        return a!=null && b!=null && a.finite() && b.finite()
                && a.minus(b).squareLength() <= 0.25*0.25;
    }

    /** Sable RapierPhysicsPipeline effect-particle position: pose.orientation
     * .transform(localContact) + pose.position; static side uses its native point. */
    private static Vec worldPoint(NativeCollisionTelemetry.Contact c,boolean sideA) {
        int id=sideA?c.idA():c.idB();
        var frame=sideA?c.frameA():c.frameB();
        Vec raw=sideA?new Vec(c.contactAX(),c.contactAY(),c.contactAZ())
                     :new Vec(c.contactBX(),c.contactBY(),c.contactBZ());
        if(!raw.finite())return null;
        if(id==-1)return raw;
        if(frame==null)return null;
        var pose=frame.pose();
        // Sable's particle path ignores non-unit scale; R3 does not silently
        // extrapolate it. Non-unit scaling requires a separate calibration.
        if(Math.abs(pose.scale().x()-1)>1e-6 || Math.abs(pose.scale().y()-1)>1e-6
                || Math.abs(pose.scale().z()-1)>1e-6)return null;
        try {return pose.toWorldPoint(raw.plus(pose.rotationPoint()));}
        catch(RuntimeException invalid){return null;}
    }
    private static boolean matches(String owner,int id,OwnerFrame frame,String dimension) {
        if(owner==null)return false;
        if(id==-1)return owner.equals("WORLD:"+dimension);
        return frame!=null && owner.equals("PLOT:"+frame.uuid());
    }
}
