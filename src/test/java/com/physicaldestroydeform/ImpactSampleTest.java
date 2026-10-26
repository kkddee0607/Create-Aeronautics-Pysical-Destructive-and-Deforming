package com.physicaldestroydeform;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ImpactSampleTest {
    @Test void validatesPrototypeData() {
        assertTrue(new ImpactSample(12.5, 3, 4).hasUsableImpact());
        assertFalse(new ImpactSample(0, 1, 1).hasUsableImpact());
        assertThrows(IllegalArgumentException.class, () -> new ImpactSample(-1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ImpactSample(1, Double.NaN, 1));
    }
}
