package com.physicaldestroydeform.diagnostics;

import com.physicaldestroydeform.BlockDamageState;
import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.ImpactSample;
import com.physicaldestroydeform.MaterialProperties;
import org.slf4j.Logger;

/**
 * Startup smoke checks for PDD's currently implemented, API-independent models.
 * These checks are synthetic and must not be mistaken for live gameplay events.
 */
public final class PddDiagnostics {
    private PddDiagnostics() {}

    public static boolean runModelSelfTest(Logger logger) {
        int checks = 0;
        try {
            MaterialProperties material = new MaterialProperties(128, 25, 40);
            logger.info("[PDD-DIAG] SELFTEST material_properties status=PASS hardness={} ductilityPercent={} elasticityPercent={}",
                    material.hardness(), material.ductilityPercent(), material.elasticityPercent());
            checks++;

            double localDamage = DamageModel.effectiveLocalDamage(10.0, material);
            double expectedDamage = 10.0 * (1.0 - (128.0 / 255.0) * 0.90) * (1.0 - 0.25 * 0.90);
            requireNear("effectiveLocalDamage", expectedDamage, localDamage);
            double neighborStress = DamageModel.neighborStress(10.0, material);
            requireNear("neighborStress", 6.0, neighborStress);
            int damageIncrement = DamageModel.damageIncrement(localDamage, 3.0);
            logger.info("[PDD-DIAG] SELFTEST damage_model status=PASS inputDamage=10.0 effectiveLocalDamage={} neighborStress={} scaledDamageIncrement={}",
                    localDamage, neighborStress, damageIncrement);
            checks++;

            ImpactSample impact = new ImpactSample(20.0, 2.0, 5.0);
            double baseDamage = DamageModel.baseDamageFromEnergy(impact, 10.0);
            requireNear("baseDamageFromEnergy", 2.0, baseDamage);
            logger.info("[PDD-DIAG] SELFTEST impact_sample status=PASS energy={} normalRelativeSpeed={} effectiveMass={} calibration={} baseDamage={}",
                    impact.impactEnergy(), impact.normalRelativeSpeed(), impact.effectiveMass(), 10.0, baseDamage);
            checks++;

            BlockDamageState state = new BlockDamageState();
            state.addDamage(7);
            state.addDeformation(3);
            state.setCrackVisual(9);
            int packed = state.pack24();
            BlockDamageState restored = BlockDamageState.unpack24(packed);
            if (restored.damage() != 7 || restored.deformation() != 3 || restored.crackVisual() != 9) {
                throw new IllegalStateException("BlockDamageState pack/unpack mismatch");
            }
            logger.info("[PDD-DIAG] SELFTEST block_damage_state status=PASS damage={} deformation={} crackVisual={} packed24=0x{} roundTrip=PASS",
                    restored.damage(), restored.deformation(), restored.crackVisual(), String.format("%06X", packed));
            checks++;

            var hit = new CollisionObservation.BlockCoordinate(1, 64, 1);
            var first = new CollisionObservation(0L, 0, "synthetic-self-test", "synthetic-local",
                    hit, null, 0.0, 0.0, 0.0, -1.25, false);
            var second = new CollisionObservation(0L, 0, "synthetic-self-test", "synthetic-local",
                    new CollisionObservation.BlockCoordinate(2, 64, 1), null, 1.0, 0.0, 0.0, 1.25, false);
            ObservationBuffer buffer = new ObservationBuffer(1);
            boolean accepted = buffer.record(first);
            boolean overflowRejected = !buffer.record(second) && buffer.droppedCount() == 1;
            if (!accepted || !overflowRejected || buffer.snapshot().size() != 1) {
                throw new IllegalStateException("ObservationBuffer bounded behavior mismatch");
            }
            logger.info("[PDD-DIAG] SELFTEST observation_buffer status=PASS capacity={} retained={} dropped={} signedVelocityPreserved={}",
                    buffer.capacity(), buffer.size(), buffer.droppedCount(), buffer.snapshot().get(0).impactVelocity() == -1.25);
            checks++;

            logger.info("[PDD-DIAG] SELFTEST_SUMMARY status=PASS checks={} scope=API_INDEPENDENT_SYNTHETIC_ONLY liveCollisionObserved=false", checks);
            return true;
        } catch (RuntimeException exception) {
            logger.error("[PDD-DIAG] SELFTEST_SUMMARY status=FAIL checksCompleted={} scope=API_INDEPENDENT_SYNTHETIC_ONLY", checks, exception);
            return false;
        }
    }

    private static void requireNear(String name, double expected, double actual) {
        if (!Double.isFinite(actual) || Math.abs(expected - actual) > 1.0e-9) {
            throw new IllegalStateException(name + " expected " + expected + " but was " + actual);
        }
    }
}
