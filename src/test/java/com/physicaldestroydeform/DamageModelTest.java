package com.physicaldestroydeform;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DamageModelTest {
    @Test
    void hardnessAndDuctilityReduceButDoNotEliminateDamage() {
        MaterialProperties material = new MaterialProperties(255, 100, 0);
        double result = DamageModel.effectiveLocalDamage(100.0, material);
        assertTrue(result > 0.0);
        assertTrue(result < 100.0);
    }

    @Test
    void elasticityControlsNeighborStressOnly() {
        assertEquals(100.0, DamageModel.neighborStress(100.0,
                new MaterialProperties(0, 0, 0)), 1e-9);
        assertEquals(0.0, DamageModel.neighborStress(100.0,
                new MaterialProperties(0, 0, 100)), 1e-9);
    }

    @Test
    void invalidOrNonPositiveInputsProduceZero() {
        MaterialProperties material = new MaterialProperties(0, 0, 0);
        assertEquals(0.0, DamageModel.effectiveLocalDamage(Double.NaN, material));
        assertEquals(0.0, DamageModel.effectiveLocalDamage(-1, material));
        assertEquals(0.0, DamageModel.neighborStress(Double.POSITIVE_INFINITY, material));
    }

@Test
void convertsImpactEnergyUsingExplicitCalibration() {
    ImpactSample sample = new ImpactSample(50.0, 2.0, 3.0);
    assertEquals(2.0, DamageModel.baseDamageFromEnergy(sample, 25.0), 1e-9);
}

@Test
void rejectsInvalidEnergyCalibrationAndNullImpact() {
    ImpactSample sample = new ImpactSample(1.0, 1.0, 1.0);
    assertThrows(IllegalArgumentException.class,
            () -> DamageModel.baseDamageFromEnergy(sample, 0.0));
    assertThrows(IllegalArgumentException.class,
            () -> DamageModel.baseDamageFromEnergy(sample, Double.NaN));
    assertThrows(IllegalArgumentException.class,
            () -> DamageModel.baseDamageFromEnergy(null, 1.0));
}

    @Test
    void quantizationIsBounded() {
        assertEquals(0, DamageModel.quantizeDamage(Double.NaN));
        assertEquals(1, DamageModel.quantizeDamage(0.2));
        assertEquals(255, DamageModel.quantizeDamage(999));
    }
}
