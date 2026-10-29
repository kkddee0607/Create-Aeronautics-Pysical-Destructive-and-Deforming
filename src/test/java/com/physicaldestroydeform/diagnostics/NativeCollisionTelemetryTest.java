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
}
