package com.physicaldestroydeform.collision;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import org.joml.Vector3dc;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Collections;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicLong;

import static com.physicaldestroydeform.collision.CollisionFrame.*;
import static com.physicaldestroydeform.collision.FrameMath.*;

/** Sable 2.0.6 bridge. No mutable world access or engine mutation from callbacks. */
public final class SableFrameCapture {
    private static final Map<SubLevelPhysicsSystem,Snapshot> ACTIVE=new ConcurrentHashMap<>();
    // POST snapshots have no production consumer. Never keep a snapshot (and its
    // server/level stamp) reachable from a process-global map after the callback.
    private static final Map<SubLevelPhysicsSystem,Boolean> LAST_POST=Collections.synchronizedMap(new WeakHashMap<>());
    // All pre-solver substeps for each server tick. Native post-tick effect
    // records do not identify their originating substep; do not guess one.
    private static final int PRE_HISTORY_LIMIT = 4096;
    private static final ArrayBlockingQueue<Snapshot> PRE_HISTORY = new ArrayBlockingQueue<>(PRE_HISTORY_LIMIT);
    private static final LongAdder HISTORY_DROPPED = new LongAdder();
    private static final AtomicLong EPOCH=new AtomicLong();
    private static final LongAdder PRE=new LongAdder(),POST=new LongAdder(),CANDIDATE=new LongAdder(),
            REJECTED=new LongAdder(),NO_EPOCH=new LongAdder(),BODY_ERRORS=new LongAdder(),
            STEP_ERRORS=new LongAdder(), POST_WITHOUT_PRE=new LongAdder();
    private SableFrameCapture() {}
    private static Vec copy(Vector3dc v) { return v==null?null:new Vec(v.x(),v.y(),v.z()); }

    private static Snapshot capture(SubLevelPhysicsSystem system,long epoch,Phase phase) {
        var level=system.getLevel();
        var stamp=ContactBatchStamp.capture(level);
        ServerSubLevelContainer container=SubLevelContainer.getContainer(level);
        var bodies=new ArrayList<Body>();
        if(container!=null)for(ServerSubLevel sub:container.getAllSubLevels()) {
            if(sub==null||sub.isRemoved())continue;
            try {
                var plot=sub.getPlot();
                var min=plot.getChunkMin();
                var max=plot.getChunkMax();
                var pose=sub.logicalPose();
                Quaterniondc q=pose.orientation();
                Pose copiedPose=new Pose(copy(pose.position()),copy(pose.rotationPoint()),
                        copy(pose.scale()),q.x(),q.y(),q.z(),q.w());
                RigidBodyHandle handle=system.getPhysicsHandle(sub);
                boolean valid=handle!=null && handle.isValid();
                Vec lin=valid?copy(handle.getLinearVelocity(new Vector3d())):null;
                Vec ang=valid?copy(handle.getAngularVelocity(new Vector3d())):null;
                var mass=sub.getMassTracker();
                Vec center=mass==null?null:copy(mass.getCenterOfMass());
                bodies.add(new Body(sub.getUniqueId(),Math.multiplyExact(min.x,16),
                        Math.addExact(Math.multiplyExact(max.x,16),15),Math.multiplyExact(min.z,16),
                        Math.addExact(Math.multiplyExact(max.z,16),15),copiedPose,lin,ang,center,valid,
                        valid && mass!=null ? SableMassAdapter.snapshot(mass,copiedPose,lin,ang) : null,
                        sub.getRuntimeId()));
            }catch(RuntimeException broken) {
                BODY_ERRORS.increment();
                // Malformed bodies are not silently mapped to a world block. See per-step validity gate.
                return new Snapshot(epoch,stamp.sourceGameTick(),stamp.dimension(),phase,bodies,false,stamp);
            }
        }
        return new Snapshot(epoch,stamp.sourceGameTick(),stamp.dimension(),phase,bodies,container!=null,stamp);
    }
    /** Event is emitted by Sable after prePhysicsTick, before solver physicsTick. */
    public static void onPre(SubLevelPhysicsSystem system) {
        long epoch=EPOCH.incrementAndGet();
        try {
            Snapshot before=capture(system,epoch,Phase.PRE_SOLVER);
            ACTIVE.put(system,before);
            if (!PRE_HISTORY.offer(before)) HISTORY_DROPPED.increment();
            PRE.increment();
        } catch (RuntimeException unsafe) {
            ACTIVE.remove(system);
            STEP_ERRORS.increment();
        }
    }
    /** Independent post snapshot is explicitly labelled; never used to reconstruct pre-impact speed. */
    public static void onPost(SubLevelPhysicsSystem system) {
        Snapshot previous=ACTIVE.remove(system);
        if(previous==null) POST_WITHOUT_PRE.increment();
        long epoch=previous==null?EPOCH.incrementAndGet():previous.epoch();
        try {
            // PRE is authoritative. Keep full POST audit available on demand,
            // but do not walk every Plot twice per substep in normal mode.
            if(com.physicaldestroydeform.diagnostics.LogSelection.enabled(com.physicaldestroydeform.diagnostics.LogSelection.Channel.NATIVE)&&com.physicaldestroydeform.diagnostics.DiagnosticMode.debug())capture(system,epoch,Phase.POST_SOLVER);
            LAST_POST.put(system,Boolean.TRUE);
            POST.increment();
        } catch (RuntimeException unsafe) {
            LAST_POST.remove(system);
            STEP_ERRORS.increment();
        }
    }
    /** Called inside the existing observer, after original Sable callback returned. */
    public static Evidence observe(int x,int y,int z,boolean hasOther,int ox,int oy,int oz,
                                   double px,double py,double pz,double signedVelocity) {
        SubLevelPhysicsSystem system;
        try {system=SubLevelPhysicsSystem.getCurrentlySteppingSystem();}
        catch(IllegalStateException notStepping) {
            NO_EPOCH.increment();return Evidence.invalid("SABLE_NOT_STEPPING");
        }
        if(system==null) {
            NO_EPOCH.increment();return Evidence.invalid("SABLE_SYSTEM_NULL");
        }
        Snapshot snapshot=ACTIVE.get(system);
        if(snapshot==null || snapshot.phase()!=Phase.PRE_SOLVER) {
            NO_EPOCH.increment();return Evidence.invalid("NO_PRE_SOLVER_SNAPSHOT");
        }
        Evidence result=snapshot.observe(x,y,z,hasOther,ox,oy,oz,px,py,pz,signedVelocity);
        (result.quality()==Quality.INVALID?REJECTED:CANDIDATE).increment();
        return result;
    }
    public static String summary() {
        return "pre="+PRE.sum()+" post="+POST.sum()+" candidates="+CANDIDATE.sum()+
                " rejected="+REJECTED.sum()+" noEpoch="+NO_EPOCH.sum()+
                " bodyErrors="+BODY_ERRORS.sum()+" stepErrors="+STEP_ERRORS.sum()+
                " postWithoutPre="+POST_WITHOUT_PRE.sum()+" activeFrames="+ACTIVE.size()+
                " lastPostFrames="+LAST_POST.size()+" preHistoryDropped="+HISTORY_DROPPED.sum()+
                " postAudit="+(com.physicaldestroydeform.diagnostics.DiagnosticMode.debug()?"FULL":"COUNTER_ONLY");
    }
    /** Dropped snapshots make owner mapping incomplete; zero credit rather than guessing. */
    public static long preHistoryDropped() { return HISTORY_DROPPED.sum(); }
    /** Consume once, after Sable's postPhysicsTicks/clearCollisions. */
    public static List<Snapshot> drainPreHistory() {
        var frames=new ArrayList<Snapshot>();
        Snapshot frame;
        while((frame=PRE_HISTORY.poll())!=null)frames.add(frame);
        return frames;
    }
    /** Called only after this server tick's physics; failed/interrupted PRE entries
     * must never retain an old world or authorize a future callback. */
    public static void releaseEndedFrames(Object server,long tick){
        ACTIVE.entrySet().removeIf(e->endedFrame(e.getValue().runtimeStamp(),server,tick));
    }
    static boolean endedFrame(ContactBatchStamp stamp,Object server,long tick){
        return !stamp.known()||stamp.serverIdentity()!=server||stamp.captureServerTick()<=tick;
    }
    public static void clear() {ACTIVE.clear();LAST_POST.clear();PRE_HISTORY.clear();}
}
