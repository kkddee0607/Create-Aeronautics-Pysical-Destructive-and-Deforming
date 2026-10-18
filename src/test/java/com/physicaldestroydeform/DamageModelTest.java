package com.physicaldestroydeform;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DamageModelTest {
    private static final double MINIMUM_HARDNESS_FACTOR = 0.10;
    private static final double GAMMA = 1.0;

    @Test
    void ductilityDoesNotChangeDirectDamage() {
        double brittle = DamageModel.effectiveLocalDamage(100.0,
                new MaterialProperties(128, 0, 0), MINIMUM_HARDNESS_FACTOR, GAMMA);
        double ductile = DamageModel.effectiveLocalDamage(100.0,
                new MaterialProperties(128, 100, 0), MINIMUM_HARDNESS_FACTOR, GAMMA);
        assertEquals(brittle, ductile, 1e-9);
    }

    @Test
    void hardnessControlsDirectDamage() {
        double soft = DamageModel.effectiveLocalDamage(100.0,
                new MaterialProperties(0, 0, 0), MINIMUM_HARDNESS_FACTOR, GAMMA);
        double hard = DamageModel.effectiveLocalDamage(100.0,
                new MaterialProperties(255, 100, 0), MINIMUM_HARDNESS_FACTOR, GAMMA);
        assertEquals(100.0, soft, 1e-9);
        assertEquals(10.0, hard, 1e-9);
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
        assertEquals(0.0, DamageModel.effectiveLocalDamage(Double.NaN, material,
                MINIMUM_HARDNESS_FACTOR, GAMMA));
        assertEquals(0.0, DamageModel.effectiveLocalDamage(-1, material,
                MINIMUM_HARDNESS_FACTOR, GAMMA));
        assertEquals(0.0, DamageModel.neighborStress(Double.POSITIVE_INFINITY, material));
    }

    @Test
    void rejectsInvalidHardnessCurveParameters() {
        MaterialProperties material = new MaterialProperties(0, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> DamageModel.effectiveLocalDamage(
                1, material, -0.1, 1));
        assertThrows(IllegalArgumentException.class, () -> DamageModel.effectiveLocalDamage(
                1, material, 0.1, 0));
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
