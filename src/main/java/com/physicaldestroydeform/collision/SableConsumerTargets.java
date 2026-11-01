package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry.OwnerFrame;
import com.physicaldestroydeform.collision.FrameMath.Vec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.HashMap;

/**
 * 0.3.0 duperFix: reproduce the ORIGINAL RapierPhysicsPipeline.processCollisionEffects()
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
    /** Candidate checks are bounded over the whole owner/pair/tick. Multiple
     * native contacts are not interchangeable manifold IDs (§21.3, §22.3). */
    static final int MAX_GROUP_CHECKS=32;
    private record AuditKey(BlockPos cell, Vec sourcePoint) {}
    static final class Scan {
        final LinkedHashSet<GameImpactCredit.Target> confirmed=new LinkedHashSet<>();
        int checked, corroborated, skipped;
        Reason missing=Reason.NO_SOLID_BLOCK;
        Result finish(String stage) {
            if(confirmed.size()>1)return new Result(null,Reason.MULTIPLE_BLOCKS,checked,stage);
            if(confirmed.size()==1)return new Result(confirmed.iterator().next(),Reason.VERIFIED,checked,
                    skipped>0?stage+"_PARTIAL":stage);
            return new Result(null,missing,checked,stage);
        }
    }
    /** A record's candidate cells are audited ONLY against that same record's
     * point. Previously mixing points from unrelated manifolds could invent
     * cross-record matches and exceeded the eight-candidate limit on ropes. */
    private static Result scan(MinecraftServer server,String dim,String owner,
                               List<NativeCollisionTelemetry.Contact> raw,boolean project,int maxChecks) {
        final String stage=project?"R3_CROSS_SIDE":"R2_CORROBORATED_GAME_PROXY";
        if(raw==null || raw.isEmpty())return new Result(null,Reason.NO_SAMPLES,0,stage);
        Scan scan=new Scan();
        // Exact duplicate native points must not repeatedly consume the cap.
        // Never merge slightly different source positions before shape audit.
        var checkedPoints=new HashMap<AuditKey,OwnerResolver.Check>();
        for(var contact:raw) {
            boolean a=matches(owner,contact.idA(),contact.frameA(),dim);
            boolean b=matches(owner,contact.idB(),contact.frameB(),dim);
            if(a==b)return new Result(null,Reason.OWNER_MISMATCH,scan.checked,stage);
            // The two source-side effect points are compared only WITHIN this
            // record, not across unrelated surface contacts.
            if(!corroboratedContact(contact)) {
                scan.skipped++;
                scan.missing=Reason.CONTACT_FRAME_UNCORROBORATED;
                continue;
            }
            Vec point;
            if(project) {
                Vec world=worldPoint(contact,!a);
                var frame=a?contact.frameA():contact.frameB();
                int id=a?contact.idA():contact.idB();
                try {point=id==-1?world:frame==null?null:frame.pose().toLocalPoint(world);}
                catch(RuntimeException invalid) {point=null;}
            } else point=candidate(contact,a);
            if(point==null || !point.finite()) {
                scan.skipped++;scan.missing=Reason.NO_NATIVE_FRAME;continue;
            }
            List<BlockPos> positions=cells(point);
            if(positions.isEmpty()) {
                scan.skipped++;scan.missing=Reason.INVALID_COORDINATE;continue;
            }
            // No record may contribute more than eight face/edge/corner cells.
            // The global group check bound also prevents O(records*candidates)
            // work for a large contact manifold; incomplete scans cannot approve.
            scan.corroborated++;
            for(var position:positions) {
                var auditKey=new AuditKey(position,point);
                var evidence=checkedPoints.get(auditKey);
                if(evidence==null) {
                    if(scan.checked>=maxChecks)
                        return new Result(null,Reason.TOO_MANY_CANDIDATES,scan.checked,stage);
                    evidence=OwnerResolver.auditNative(server,dim,owner,position,point);
                    checkedPoints.put(auditKey,evidence);
                    scan.checked++;
                }
                if(!evidence.verified())continue;
                var key=evidence.key();
                scan.confirmed.add(new GameImpactCredit.Target(owner,
                        key.x()+","+key.y()+","+key.z(),key.fingerprint()));
                if(scan.confirmed.size()>1)
                    return new Result(null,Reason.MULTIPLE_BLOCKS,scan.checked,stage);
            }
        }
        if(scan.corroborated==0 && scan.skipped==0)scan.missing=Reason.NO_SAMPLES;
        return scan.finish(stage);
    }
    public static Result resolve(MinecraftServer server,String dim,String stableOwner,
                                 List<NativeCollisionTelemetry.Contact> raw) {
        return scan(server,dim,stableOwner,raw,false,MAX_GROUP_CHECKS);
    }
    public static Result crossProject(MinecraftServer server,String dim,String targetOwner,
                                      List<NativeCollisionTelemetry.Contact> raw) {
        return scan(server,dim,targetOwner,raw,true,MAX_GROUP_CHECKS);
    }
    /** Compare the two source records in world coordinates, never by raw
     * collider-local numerical equality. This is diagnostic, not a new target. */
    public record FrameResidual(Vec a,Vec b,Vec delta,double distance,String reason) {
        public boolean corroborated() {return "MATCH".equals(reason);}
    }
    public static FrameResidual frameResidual(NativeCollisionTelemetry.Contact c) {
        if(c==null)return new FrameResidual(null,null,null,Double.NaN,"NULL_RECORD");
        Vec a=worldPoint(c,true),b=worldPoint(c,false);
        if(a==null || b==null || !a.finite() || !b.finite())
            return new FrameResidual(a,b,null,Double.NaN,"MISSING_FRAME_OR_UNSUPPORTED_SCALE");
        Vec delta=a.minus(b);
        double distance=Math.sqrt(delta.squareLength());
        return new FrameResidual(a,b,delta,distance,
                Double.isFinite(distance) && distance<=0.25?"MATCH":"WORLD_POINT_RESIDUAL_GT_0.25");
    }
    /** A bounded cross-side fallback may use the exact remaining group quota.
     * A limit hit returns TOO_MANY_CANDIDATES rather than a partial target. */
    public static Result crossProject(MinecraftServer server,String dim,String targetOwner,
                                      List<NativeCollisionTelemetry.Contact> raw,int maxChecks) {
        if(maxChecks<0 || maxChecks>MAX_GROUP_CHECKS)
            throw new IllegalArgumentException("invalid remaining R3 budget");
        return scan(server,dim,targetOwner,raw,true,maxChecks);
    }
    /** Same native record, two independent contact sides, effect-consumer phase.
     * This is a necessary cross-check, not an exact solver collider-pose proof. */
    static boolean corroboratedContact(NativeCollisionTelemetry.Contact c) {
        if(c==null)return false;
        return frameResidual(c).corroborated();
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
