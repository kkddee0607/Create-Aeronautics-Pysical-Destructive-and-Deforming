package com.physicaldestroydeform;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ImpactSampleTest {
    @Test
    void acceptsFiniteNonNegativeImpactData() {
        ImpactSample sample = new ImpactSample(12.5, 3.0, 4.0);
        assertTrue(sample.hasUsableImpact());
    }

    @Test
    void rejectsNegativeOrNonFiniteValues() {
        assertThrows(IllegalArgumentException.class, () -> new ImpactSample(-1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ImpactSample(1, Double.NaN, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ImpactSample(1, 1, Double.POSITIVE_INFINITY));
    }

    @Test
    void zeroEnergyOrZeroNormalSpeedIsNotUsableImpact() {
        assertFalse(new ImpactSample(0, 1, 1).hasUsableImpact());
        assertFalse(new ImpactSample(1, 0, 1).hasUsableImpact());
    }
}
