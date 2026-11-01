package com.physicaldestroydeform.diagnostics;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public class NativeCollisionTelemetryTest {
    @Test void nativeLayoutIsPreserved() {
        NativeCollisionTelemetry.drain();
        long count = NativeCollisionTelemetry.total();
        long calls = NativeCollisionTelemetry.polls();
        double[] raw = {1,2,3,4,5,6,7,8,9,10,11,12,13,14,15};
        NativeCollisionTelemetry.capture(raw);
        assertArrayEquals(new double[]{1,2,3,4,5,6,7,8,9,10,11,12,13,14,15},raw);
        assertEquals(calls+1, NativeCollisionTelemetry.polls());
        assertEquals(count+1, NativeCollisionTelemetry.total());
        var items=NativeCollisionTelemetry.drain();
        assertEquals(1,items.size());
        var c=items.getFirst();
        assertEquals(1,c.idA()); assertEquals(2,c.idB());
        assertEquals(3,c.rawImpactScalar()); assertEquals(12,c.contactAZ());
        assertEquals(15,c.contactBZ());
        if (System.getProperty("pdd.debugCollisions") == null) assertTrue(NativeCollisionTelemetry.ENABLED);
    }
    @Test void malformedArrayIsVisible() {
        var initial=NativeCollisionTelemetry.malformed();
        NativeCollisionTelemetry.capture(new double[]{1,2});
        assertEquals(initial+1, NativeCollisionTelemetry.malformed());
    }
    @Test void exactEffectPhaseOwnerSnapshotDoesNotAlterOriginalArray() {
        NativeCollisionTelemetry.drain();
        double[] raw={9,-1,42,1,0,0,-1,0,0,0.1,0.2,0.3,4,5,6};
        double[] before=raw.clone();
        var uuid=java.util.UUID.fromString("10000000-0000-0000-0000-000000000001");
        var pose=new com.physicaldestroydeform.collision.FrameMath.Pose(
                new com.physicaldestroydeform.collision.FrameMath.Vec(10,20,30),
                new com.physicaldestroydeform.collision.FrameMath.Vec(512,64,1024),
                new com.physicaldestroydeform.collision.FrameMath.Vec(1,1,1),0,0,0,1);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        NativeCollisionTelemetry.capture(raw,"minecraft:overworld",12,id->{
            calls.incrementAndGet();
            return new NativeCollisionTelemetry.OwnerFrame(uuid,pose);
        });
        assertArrayEquals(before,raw);
        var sample=NativeCollisionTelemetry.drain().getFirst();
        assertEquals(1,calls.get()); // -1 WORLD sentinel is not a Plot UUID lookup
        assertEquals(uuid,sample.frameA().uuid());
        assertNull(sample.frameB());
    }
}
