package com.physicaldestroydeform.material;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class HardnessScaleTest {
    @Test void zeroAndExtremeInputsAreBounded() {
        assertEquals(0, HardnessScale.fromMinecraft(0, 0));
        assertEquals(255, HardnessScale.fromMinecraft(-1, 0));
        assertEquals(255, HardnessScale.fromMinecraft(50, 1200));
        assertEquals(255, HardnessScale.fromMinecraft(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY));
        assertEquals(0, HardnessScale.fromMinecraft(Double.NaN, Double.NaN));
    }
    @Test void scoreIsMonotonic() {
        int previous = 0;
        for (int i = 0; i < 1000; i++) {
            int next = HardnessScale.fromMinecraft(i * 0.2, i * 0.5);
            assertTrue(next >= previous && next <= 255);
            previous = next;
        }
    }
}
