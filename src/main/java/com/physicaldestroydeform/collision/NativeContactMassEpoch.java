package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry.Contact;
import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry.OwnerFrame;
import com.physicaldestroydeform.collision.FrameMath.Vec;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;

/** Native effect contacts omit their originating substep. Validate that their
 * Plot-local origin (COM) stayed constant throughout all observed pre-substeps.
 * Body orientation/position are irrelevant to owner-local R2, unlike R3.
 * No global/static cache: one instance per server tick processing batch. */
public final class NativeContactMassEpoch {
    private static final double EPS_SQUARED=1.0e-8; // 0.0001 block
    private record Stamp(String dimension,long tick,int runtimeId) {}
    private record Tick(String dimension,long tick) {}
    private static final class Epoch {
        UUID uuid;
        Vec center;
        boolean stable=true;
        int samples;
        void add(CollisionFrame.Body b) {
            samples++;
            Vec next=b.pose()==null?null:b.pose().rotationPoint();
            if(b.plotId()==null || next==null || !next.finite() ||
                    b.centerRaw()==null || !close(next,b.centerRaw())) {
                stable=false;return;
            }
            if(uuid==null) {uuid=b.plotId();center=next;}
            else if(!uuid.equals(b.plotId()) || !close(center,next))stable=false;
        }
        boolean matches(OwnerFrame frame,int expectedSamples) {
            if(!stable || uuid==null || frame==null || samples!=expectedSamples
                    || !uuid.equals(frame.uuid()) || !close(center,frame.pose().rotationPoint()))
                return false;
            // Rapier LevelCollider has no scale transform; unknown non-unit
            // Sable Pose scale must not silently be promoted to R2 evidence.
            Vec scale=frame.pose().scale();
            return scale!=null && scale.finite()
                    && Math.abs(scale.x()-1)<=1e-6 && Math.abs(scale.y()-1)<=1e-6
                    && Math.abs(scale.z()-1)<=1e-6;
        }
    }
    private static boolean close(Vec a,Vec b) {
        return a!=null && b!=null && a.finite() && b.finite()
                && a.minus(b).squareLength()<=EPS_SQUARED;
    }
    private final Map<Stamp,Epoch> origins=new HashMap<>();
    private final Map<Tick,Integer> substeps=new HashMap<>();
    public NativeContactMassEpoch(List<CollisionFrame.Snapshot> snapshots) {
        for(var snapshot:snapshots) {
            if(snapshot==null || snapshot.phase()!=CollisionFrame.Phase.PRE_SOLVER
                    || snapshot.dimension()==null || snapshot.serverTick()<0)continue;
            substeps.merge(new Tick(snapshot.dimension(),snapshot.serverTick()),1,Integer::sum);
            for(var body:snapshot.bodies()) {
                if(body==null || body.runtimeId()<0)continue;
                origins.computeIfAbsent(new Stamp(snapshot.dimension(),snapshot.serverTick(),body.runtimeId()),
                        ignored->new Epoch()).add(body);
            }
        }
    }
    public record Result(List<Contact> contacts,int maskedFrames) {
        public Result {contacts=List.copyOf(contacts);}
    }
    /** Failed epoch proof removes ONLY affected owner frames. The opposite
     * native owner may still pass its own shape audit and retain its 50% Q. */
    public Result validate(String dimension,long tick,List<Contact> raw) {
        var result=new ArrayList<Contact>(raw.size());
        int masked=0;
        for(var c:raw) {
            OwnerFrame a=c.frameA(),b=c.frameB();
            if(c.idA()>=0 && !valid(dimension,tick,c.idA(),a)) {a=null;masked++;}
            if(c.idB()>=0 && !valid(dimension,tick,c.idB(),b)) {b=null;masked++;}
            if(a==c.frameA() && b==c.frameB())result.add(c);
            else result.add(new Contact(c.idA(),c.idB(),c.rawImpactScalar(),
                    c.ax(),c.ay(),c.az(),c.bx(),c.by(),c.bz(),
                    c.contactAX(),c.contactAY(),c.contactAZ(),
                    c.contactBX(),c.contactBY(),c.contactBZ(),
                    c.dimension(),c.serverTick(),a,b));
        }
        return new Result(result,masked);
    }
    private boolean valid(String dimension,long tick,int id,OwnerFrame frame) {
        var epoch=origins.get(new Stamp(dimension,tick,id));
        return epoch!=null && epoch.matches(frame,
                substeps.getOrDefault(new Tick(dimension,tick),0));
    }
}
