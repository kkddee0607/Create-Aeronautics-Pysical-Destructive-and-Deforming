package com.physicaldestroydeform.diagnostics;

import com.physicaldestroydeform.BlockDamageState;
import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.ImpactSample;
import com.physicaldestroydeform.collision.ImpactMath;
import com.physicaldestroydeform.collision.FrameMath.Vec;
import com.physicaldestroydeform.MaterialProperties;
import com.physicaldestroydeform.material.HardnessScale;
import org.slf4j.Logger;

/** Synthetic model sanity checks. These are NOT evidence of live Sable integration. */
public final class PddDiagnostics {
    private PddDiagnostics() {}
    public static boolean runModelSelfTest(Logger logger) {
        int checks = 0;
        try {
            MaterialProperties material = new MaterialProperties(128, 25, 40);
            logger.info("[PDD-DIAG] SELFTEST material_properties status=PASS hardness={} ductilityPercent={} elasticityPercent={}",
                    material.hardness(), material.ductilityPercent(), material.elasticityPercent());
            checks++;
            double direct = DamageModel.effectiveLocalDamage(10, material);
            near(10.0 * (1.0 - (128.0 / 255.0) * 0.9), direct);
            near(direct, DamageModel.effectiveLocalDamage(10, new MaterialProperties(128, 100, 40)));
            near(6.0, DamageModel.neighborStress(10, material));
            logger.info("[PDD-DIAG] SELFTEST damage_model status=PASS directDamage={} ductilityAffectsDamage=false neighborStress=6", direct);
            checks++;
            ImpactSample impact = new ImpactSample(20, 2, 5);
            near(2, DamageModel.baseDamageFromEnergy(impact, 10));
            logger.info("[PDD-DIAG] SELFTEST impact_sample status=PASS scope=SYNTHETIC_ONLY");
            checks++;
            BlockDamageState state = new BlockDamageState();
            state.addDamage(7); state.addDeformation(3); state.setCrackVisual(9);
            BlockDamageState roundtrip = BlockDamageState.unpack24(state.pack24());
            if (roundtrip.damage() != 7 || roundtrip.deformation() != 3 || roundtrip.crackVisual() != 9)
                throw new IllegalStateException("packed state mismatch");
            logger.info("[PDD-DIAG] SELFTEST block_damage_state status=PASS packed24={}", state.pack24());
            checks++;
            var hit = new CollisionObservation.BlockCoordinate(1, 64, 1);
            var record = new CollisionObservation(0, 0, "synthetic", "unknown", hit, null, 0, 0, 0, -1.25, false);
            ObservationBuffer buffer = new ObservationBuffer(1);
            if (!buffer.record(record) || buffer.record(record) || buffer.droppedCount() != 1)
                throw new IllegalStateException("bounded observation mismatch");
            logger.info("[PDD-DIAG] SELFTEST observation_buffer status=PASS capacity=1 dropped=1");
            checks++;
            if (HardnessScale.fromMinecraft(0, 0) != 0 || HardnessScale.fromMinecraft(-1, 0) != 255
                    || HardnessScale.fromMinecraft(50, 1200) != 255)
                throw new IllegalStateException("hardness conversion boundaries mismatch");
            logger.info("[PDD-DIAG] SELFTEST hardness_scale status=PASS empty=0 unbreakable=255 saturated=255");
            checks++;
            Vec zero=new Vec(0,0,0), normal=new Vec(1,0,0);
            var rigid=new ImpactMath.Body(false,2,0.5,ImpactMath.Tensor.zero(),zero,
                    new Vec(-4,0,0),zero);
            var exact=ImpactMath.exact(new ImpactMath.ExactInput(rigid,
                    ImpactMath.Body.staticWorld(),zero,normal,true,true,true));
            near(16,exact.budget());
            if(exact.kind()!=ImpactMath.Kind.EXACT_CONTACT || !exact.approved())
                throw new IllegalStateException("exact math model rejected synthetic case");
            var proxy=ImpactMath.proxy(new ImpactMath.ProxyInput(rigid,
                    ImpactMath.Body.staticWorld(),normal,true,true,true,false),
                    new ImpactMath.ProxyPolicy(true,.5,10));
            near(8,proxy.budget());
            if(proxy.kind()!=ImpactMath.Kind.CONSERVATIVE_PROXY || !proxy.approved())
                throw new IllegalStateException("proxy math model rejected synthetic case");
            if(ImpactMath.proxy(new ImpactMath.ProxyInput(rigid,
                    ImpactMath.Body.staticWorld(),normal,true,true,false,false),
                    new ImpactMath.ProxyPolicy(true,.5,10)).approved())
                throw new IllegalStateException("unverified normal approved");
            logger.info("[PDD-G-MATH-01] SELFTEST status=PASS exactSynthetic={} proxySynthetic={} " +
                    "directionRejected=true scope=API_INDEPENDENT_SYNTHETIC_ONLY",exact.budget(),proxy.budget());
            checks++;
            logger.info("[PDD-DIAG] SELFTEST_SUMMARY status=PASS checks={} scope=API_INDEPENDENT_SYNTHETIC_ONLY liveCollisionObserved=false", checks);
            return true;
        } catch (RuntimeException exception) {
            logger.error("[PDD-DIAG] SELFTEST_SUMMARY status=FAIL completed={}", checks, exception);
            return false;
        }
    }
    private static void near(double expected, double actual) {
        if (!Double.isFinite(actual) || Math.abs(expected - actual) > 1e-9)
            throw new IllegalStateException("expected " + expected + ", actual " + actual);
    }
}
