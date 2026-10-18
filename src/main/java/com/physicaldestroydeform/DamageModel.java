package com.physicaldestroydeform;

/**
 * Pure gameplay calculations for PDD.
 *
 * <p>These functions do not assume that a Sable callback value is energy. A verified
 * integration layer must first create an {@link ImpactSample} with calibrated values.</p>
 */
public final class DamageModel {
    private DamageModel() {}

    /**
     * Applies the PDD hardness curve to already-normalized base damage.
     * Ductility deliberately does not reduce direct damage; it belongs to the separate
     * permanent-deformation calculation. The curve parameters are explicit because the
     * API/design evidence does not yet establish calibrated defaults.
     *
     * @param impactDamage finite normalized base damage
     * @param material material properties for the struck block
     * @param minimumHardnessFactor minimum fraction of base damage retained at hardness 255,
     *                              in the inclusive range [0, 1]
     * @param gamma positive hardness-curve exponent
     * @return damage after hardness attenuation
     */
    public static double effectiveLocalDamage(
            double impactDamage,
            MaterialProperties material,
            double minimumHardnessFactor,
            double gamma) {
        if (material == null) throw new IllegalArgumentException("material must not be null");
        if (!Double.isFinite(minimumHardnessFactor)
                || minimumHardnessFactor < 0.0 || minimumHardnessFactor > 1.0) {
            throw new IllegalArgumentException("minimumHardnessFactor must be finite and in [0, 1]");
        }
        if (!Double.isFinite(gamma) || gamma <= 0.0) {
            throw new IllegalArgumentException("gamma must be finite and > 0");
        }
        if (!Double.isFinite(impactDamage) || impactDamage <= 0.0) return 0.0;

        double normalizedHardness = material.hardnessNormalized();
        double hardnessFactor = minimumHardnessFactor
                + (1.0 - minimumHardnessFactor) * Math.pow(1.0 - normalizedHardness, gamma);
        return impactDamage * hardnessFactor;
    }

    /**
     * Calculates the PDD share of residual stress passed to neighboring blocks.
     * This is an addon rule, not Sable's physical restitution coefficient.
     */
    public static double neighborStress(double residualStress, MaterialProperties material) {
        if (material == null) throw new IllegalArgumentException("material must not be null");
        if (!Double.isFinite(residualStress) || residualStress <= 0.0) return 0.0;
        return residualStress * (1.0 - material.elasticity());
    }

    /**
     * Converts impact energy into normalized base damage using an explicit calibration.
     * This does not infer units or convert a raw Sable callback velocity to energy.
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
        if (!Double.isFinite(damage) || damage <= 0.0) return 0;
        return (int) Math.min(255, Math.ceil(damage));
    }

    /** Converts normalized damage into a bounded increment for the stored damage meter. */
    public static int damageIncrement(double effectiveDamage, double damageScale) {
        if (!Double.isFinite(effectiveDamage) || effectiveDamage <= 0.0
                || !Double.isFinite(damageScale) || damageScale <= 0.0) {
            return 0;
        }
        return quantizeDamage(effectiveDamage * damageScale);
    }
}
