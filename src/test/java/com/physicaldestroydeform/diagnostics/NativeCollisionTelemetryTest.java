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
        assertEquals(-1,c.captureServerTick());assertFalse(c.runtimeStamp().known());
        assertFalse(c.capSaturated());assertEquals(calls+1,c.pollId());
        assertEquals(3,c.rawImpactScalar()); assertEquals(12,c.contactAZ());
        assertEquals(15,c.contactBZ());
        if (System.getProperty("pdd.debugCollisions") == null) assertTrue(NativeCollisionTelemetry.ENABLED);
    }
    @Test void stampedNativePollRetainsSourceClockAndIdentityThroughDrain(){
        NativeCollisionTelemetry.drain();
        var stamp=new com.physicaldestroydeform.collision.ContactBatchStamp(new Object(),new Object(),
                "minecraft:overworld",720,77577);
        double[] raw={9,-1,42,1,0,0,-1,0,0,.1,.2,.3,4,5,6};
        NativeCollisionTelemetry.capture(raw,stamp,null);
        var c=NativeCollisionTelemetry.drain().getFirst();
        assertSame(stamp,c.runtimeStamp());assertEquals(720,c.captureServerTick());
        assertEquals(77577,c.serverTick());
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
    @Test void oneEffectPollFreezesEachBodyFrameOnceWithoutChangingRawRecords(){
        NativeCollisionTelemetry.drain();
        double[] one={9,-1,42,1,0,0,-1,0,0,.1,.2,.3,4,5,6};
        double[] raw=new double[one.length*3];for(int i=0;i<3;i++)System.arraycopy(one,0,raw,i*one.length,one.length);
        double[] before=raw.clone();var calls=new java.util.concurrent.atomic.AtomicInteger();
        var zero=new com.physicaldestroydeform.collision.FrameMath.Vec(0,0,0);
        var pose=new com.physicaldestroydeform.collision.FrameMath.Pose(zero,zero,new com.physicaldestroydeform.collision.FrameMath.Vec(1,1,1),0,0,0,1);
        var uuid=java.util.UUID.fromString("10000000-0000-0000-0000-000000000001");
        NativeCollisionTelemetry.capture(raw,"minecraft:overworld",12,id->{calls.incrementAndGet();return new NativeCollisionTelemetry.OwnerFrame(uuid,pose);});
        var samples=NativeCollisionTelemetry.drain();assertEquals(3,samples.size());assertEquals(1,calls.get());
        assertArrayEquals(before,raw);assertSame(samples.getFirst().frameA(),samples.getLast().frameA());
        assertTrue(samples.getFirst().pollId()>0);assertTrue(samples.stream().allMatch(c->c.pollId()==samples.getFirst().pollId()));
        NativeCollisionTelemetry.capture(one,"minecraft:overworld",12);var second=NativeCollisionTelemetry.drain().getFirst();
        assertTrue(second.pollId()>samples.getFirst().pollId());
    }
    @Test void nativeHundredRecordCapSaturationIsNotReportedAsConfirmedDrop() {
        NativeCollisionTelemetry.drain();
        long before=NativeCollisionTelemetry.nativeCapSaturatedPolls();
        long dropped=NativeCollisionTelemetry.dropped();
        double[] records=new double[NativeCollisionTelemetry.NATIVE_EFFECT_MAX_RECORDS
                *NativeCollisionTelemetry.FIELDS];
        for(int i=0;i<NativeCollisionTelemetry.NATIVE_EFFECT_MAX_RECORDS;i++) {
            int o=i*NativeCollisionTelemetry.FIELDS;
            records[o]=9;records[o+1]=-1;records[o+2]=42;
        }
        NativeCollisionTelemetry.capture(records,"minecraft:overworld",40);
        assertEquals(before+1,NativeCollisionTelemetry.nativeCapSaturatedPolls());
        assertEquals(dropped,NativeCollisionTelemetry.dropped());
        var captured=NativeCollisionTelemetry.drain();
        assertEquals(NativeCollisionTelemetry.NATIVE_EFFECT_MAX_RECORDS,captured.size());
        assertTrue(captured.stream().allMatch(NativeCollisionTelemetry.Contact::capSaturated));
    }

}
