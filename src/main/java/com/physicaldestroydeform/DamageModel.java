package com.physicaldestroydeform;

/**
 * Pure calculations for PDD's early prototype.
 *
 * <p>These are gameplay tuning rules, not SI-unit material physics. They are not yet
 * connected to Sable collision events.</p>
 */
public final class DamageModel {
    /**
     * Maximum reduction applied independently by hardness and ductility.
     * A cap below 1 prevents either property from making a block completely immune.
     */
    public static final double MAX_REDUCTION_PER_PROPERTY = 0.90;

    private DamageModel() {}

    /**
     * Calculates local damage before accumulation.
     *
     * @param impactDamage finite, nonnegative normalized damage input
     * @param material block-type material properties
     * @return nonnegative effective damage
     */
    public static double effectiveLocalDamage(double impactDamage, MaterialProperties material) {
        if (material == null) throw new IllegalArgumentException("material must not be null");
        if (!Double.isFinite(impactDamage) || impactDamage <= 0) return 0;

        double hardnessReduction = material.hardnessNormalized() * MAX_REDUCTION_PER_PROPERTY;
        double ductilityReduction = material.ductility() * MAX_REDUCTION_PER_PROPERTY;
        return impactDamage * (1.0 - hardnessReduction) * (1.0 - ductilityReduction);
    }

    /**
     * Calculates the share of residual stress passed to neighboring blocks.
     * Elasticity is an addon rule and is not Sable's physical restitution coefficient.
     */
    public static double neighborStress(double residualStress, MaterialProperties material) {
        if (material == null) throw new IllegalArgumentException("material must not be null");
        if (!Double.isFinite(residualStress) || residualStress <= 0) return 0;
        return residualStress * (1.0 - material.elasticity());
    }

/**
 * Converts impact energy into a normalized base-damage input.
 *
 * <p>{@code energyPerDamageUnit} is a gameplay calibration parameter, not a physical
 * constant. This method intentionally does not infer a Sable API or assume a specific
 * energy unit. A caller must supply energy and calibration in matching units.</p>
 *
 * @param impact a validated, API-independent impact sample
 * @param energyPerDamageUnit positive energy required for one base damage unit
 * @return normalized base damage, or zero for a zero-energy/non-impact sample
 */
public static double baseDamageFromEnergy(ImpactSample impact, double energyPerDamageUnit) {
    if (impact == null) throw new IllegalArgumentException("impact must not be null");
    if (!Double.isFinite(energyPerDamageUnit) || energyPerDamageUnit <= 0.0) {
        throw new IllegalArgumentException("energyPerDamageUnit must be finite and > 0");
    }
    if (!impact.hasUsableImpact()) return 0.0;
    return impact.impactEnergy() / energyPerDamageUnit;
}

    /** Quantizes positive damage upward to an unsigned-byte-compatible 0..255 value. */
    public static int quantizeDamage(double damage) {
        if (!Double.isFinite(damage) || damage <= 0) return 0;
        return (int) Math.min(255, Math.ceil(damage));
    }

    /**
     * Converts normalized damage into an increment for the stored 0..255 damage meter.
     * This mapping is a prototype rule and must be tuned against actual collision data.
     */
    public static int damageIncrement(double effectiveDamage, double damageScale) {
        if (!Double.isFinite(effectiveDamage) || effectiveDamage <= 0
                || !Double.isFinite(damageScale) || damageScale <= 0) {
            return 0;
        }
        return quantizeDamage(effectiveDamage * damageScale);
    }
}
