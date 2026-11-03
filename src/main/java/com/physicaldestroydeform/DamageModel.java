package com.physicaldestroydeform;

/** Prototype calculations only: never used for world damage in 0.2.12. */
public final class DamageModel {
    public static final double MAX_REDUCTION_PER_PROPERTY = 0.90;
    private DamageModel() {}
    public static double effectiveLocalDamage(double impactDamage, MaterialProperties material) {
        if (material == null) throw new IllegalArgumentException("material must not be null");
        if (!Double.isFinite(impactDamage) || impactDamage <= 0) return 0;
        double hardnessReduction = material.hardnessNormalized() * MAX_REDUCTION_PER_PROPERTY;
        return impactDamage * (1.0 - hardnessReduction);
    }
    public static double neighborStress(double residualStress, MaterialProperties material) {
        if (material == null) throw new IllegalArgumentException("material must not be null");
        if (!Double.isFinite(residualStress) || residualStress <= 0) return 0;
        return residualStress * (1.0 - material.elasticity());
    }
    public static double baseDamageFromEnergy(ImpactSample impact, double energyPerDamageUnit) {
        if (impact == null) throw new IllegalArgumentException("impact must not be null");
        if (!Double.isFinite(energyPerDamageUnit) || energyPerDamageUnit <= 0.0)
            throw new IllegalArgumentException("energyPerDamageUnit must be finite and > 0");
        if (!impact.hasUsableImpact()) return 0.0;
        return impact.impactEnergy() / energyPerDamageUnit;
    }
    public static int quantizeDamage(double damage) {
        if (!Double.isFinite(damage) || damage <= 0) return 0;
        return (int) Math.min(255, Math.ceil(damage));
    }
    public static int damageIncrement(double effectiveDamage, double damageScale) {
        if (!Double.isFinite(effectiveDamage) || effectiveDamage <= 0
                || !Double.isFinite(damageScale) || damageScale <= 0) return 0;
        return quantizeDamage(effectiveDamage * damageScale);
    }
}
