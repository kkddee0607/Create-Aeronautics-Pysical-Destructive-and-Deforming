package com.physicaldestroydeform.material;

/**
 * Deterministic heuristic for Minecraft mining/explosion resistance -> PDD byte hardness.
 * This is a tuning score, not physical hardness and not a destruction threshold.
 */
public final class HardnessScale {
    private HardnessScale() {}
    public static int fromMinecraft(double destroySpeed, double explosionResistance) {
        if (destroySpeed < 0.0) return 255; // unbreakable by mining
        double mining = fraction(destroySpeed, 50.0);
        double blast = fraction(explosionResistance, 1200.0);
        return (int) Math.round(255.0 * (0.65 * mining + 0.35 * blast));
    }
    private static double fraction(double value, double reference) {
        if (Double.isNaN(value) || value <= 0.0) return 0.0;
        if (Double.isInfinite(value)) return 1.0;
        return Math.min(1.0, Math.log1p(value) / Math.log1p(reference));
    }
}
