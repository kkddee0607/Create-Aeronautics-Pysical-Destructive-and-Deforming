package com.physicaldestroydeform;

/** Pure math for the 0.1 prototype; not yet connected to Sable collision events. */
public final class DamageModel {
    /** Keep some damage possible even at maximum hardness/ductility. */
    public static final double MAX_REDUCTION_PER_PROPERTY = 0.90;

    private DamageModel() {}

    /**
     * Returns effective local damage before accumulation.
     * impactDamage is a normalized game-damage input, not physical impulse in SI units.
     */
    public static double effectiveLocalDamage(double impactDamage, MaterialProperties material) {
        if (!Double.isFinite(impactDamage) || impactDamage <= 0) return 0;
        double hardness = material.hardness() / 255.0;
        double hardnessReduction = hardness * MAX_REDUCTION_PER_PROPERTY;
        double ductilityReduction = material.ductility() * MAX_REDUCTION_PER_PROPERTY;
        return impactDamage * (1.0 - hardnessReduction) * (1.0 - ductilityReduction);
    }

    /** Returns the share of residual impact forwarded to neighboring blocks. */
    public static double neighborStress(double residualStress, MaterialProperties material) {
        if (!Double.isFinite(residualStress) || residualStress <= 0) return 0;
        return residualStress * (1.0 - material.elasticity());
    }

    /** Maps a nonnegative damage amount to the 0..255 persistent damage range. */
    public static int quantizeDamage(double damage) {
        if (!Double.isFinite(damage) || damage <= 0) return 0;
        return Math.max(0, Math.min(255, (int) Math.ceil(damage)));
    }
}
