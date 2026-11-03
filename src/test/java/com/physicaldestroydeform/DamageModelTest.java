package com.physicaldestroydeform;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DamageModelTest {
    @Test void hardnessReducesDamageButNeverMakesItZero() {
        assertEquals(100,DamageModel.effectiveLocalDamage(100,new MaterialProperties(0,0,0)),1e-9);
        assertEquals(8,DamageModel.effectiveLocalDamage(100,new MaterialProperties(255,100,0)),1e-9);
    }
    @Test void ductilityDoesNotChangeDirectDamage() {
        assertEquals(DamageModel.effectiveLocalDamage(100,new MaterialProperties(100,0,0)),
                DamageModel.effectiveLocalDamage(100,new MaterialProperties(100,100,0)));
    }
    @Test void elasticityAffectsOnlyNewNodeAdmission() {
        assertEquals(100,DamageModel.neighborStress(100,new MaterialProperties(0,0,0)));
        assertEquals(15,DamageModel.neighborStress(100,new MaterialProperties(0,0,100)),1e-9);
    }
    @Test void energyPrototypeRejectsUncalibratedScale() {
        assertThrows(IllegalArgumentException.class,
                ()->DamageModel.baseDamageFromEnergy(new ImpactSample(1,1,1),0));
        assertEquals(2,DamageModel.baseDamageFromEnergy(new ImpactSample(50,2,3),25));
    }
    @Test void quantizationClampsAndDoesNotRoundUpSmallDamage() {
        assertEquals(0,DamageModel.quantizeDamage(Double.NaN));
        assertEquals(0,DamageModel.quantizeDamage(0.2));
        assertEquals(255,DamageModel.quantizeDamage(999));
    }
}
