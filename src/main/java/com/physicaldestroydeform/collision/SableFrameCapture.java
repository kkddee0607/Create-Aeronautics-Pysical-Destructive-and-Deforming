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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicLong;

import static com.physicaldestroydeform.collision.CollisionFrame.*;
import static com.physicaldestroydeform.collision.FrameMath.*;

/** Sable 2.0.6 bridge. No mutable world access or engine mutation from callbacks. */
public final class SableFrameCapture {
    private static final Map<SubLevelPhysicsSystem,Snapshot> ACTIVE=new ConcurrentHashMap<>();
    private static final Map<SubLevelPhysicsSystem,Snapshot> LAST_POST=new ConcurrentHashMap<>();
    private static final AtomicLong EPOCH=new AtomicLong();
    private static final LongAdder PRE=new LongAdder(),POST=new LongAdder(),CANDIDATE=new LongAdder(),
            REJECTED=new LongAdder(),NO_EPOCH=new LongAdder(),BODY_ERRORS=new LongAdder(),
            STEP_ERRORS=new LongAdder(), POST_WITHOUT_PRE=new LongAdder();
    private SableFrameCapture() {}
    private static Vec copy(Vector3dc v) { return v==null?null:new Vec(v.x(),v.y(),v.z()); }

    private static Snapshot capture(SubLevelPhysicsSystem system,long epoch,Phase phase) {
        var level=system.getLevel();
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
                        Math.addExact(Math.multiplyExact(max.z,16),15),copiedPose,lin,ang,center,valid));
            }catch(RuntimeException broken) {
                BODY_ERRORS.increment();
                // Malformed bodies are not silently mapped to a world block. See per-step validity gate.
                return new Snapshot(epoch,level.getGameTime(),level.dimension().location().toString(),
                        phase,bodies,false);
            }
        }
        return new Snapshot(epoch,level.getGameTime(),level.dimension().location().toString(),
                phase,bodies,container!=null);
    }
    /** Event is emitted by Sable after prePhysicsTick, before solver physicsTick. */
    public static void onPre(SubLevelPhysicsSystem system) {
        long epoch=EPOCH.incrementAndGet();
        try {
            ACTIVE.put(system,capture(system,epoch,Phase.PRE_SOLVER));
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
            LAST_POST.put(system,capture(system,epoch,Phase.POST_SOLVER));
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
                " lastPostFrames="+LAST_POST.size();
    }
    public static void clear() {ACTIVE.clear();LAST_POST.clear();}
}
