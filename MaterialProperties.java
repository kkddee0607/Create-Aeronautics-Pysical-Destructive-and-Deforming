package com.physicaldestroydeform;

/** Immutable per-block-type defaults. Share instances; do not allocate one per block. */
public record MaterialProperties(int hardness, int ductilityPercent, int elasticityPercent) {
    public MaterialProperties {
        hardness = clampByte(hardness);
        ductilityPercent = clampPercent(ductilityPercent);
        elasticityPercent = clampPercent(elasticityPercent);
    }

    /** Hardness is a 0..255 damage-reduction rating, not vanilla block hardness. */
    public int hardness() { return hardness; }

    /** Ductility is stored as an integer percentage, 0..100, representing 0..1. */
    public int ductilityPercent() { return ductilityPercent; }

    /** Elasticity is stored as an integer percentage, 0..100, representing 0..1. */
    public int elasticityPercent() { return elasticityPercent; }

    public double ductility() { return ductilityPercent / 100.0; }
    public double elasticity() { return elasticityPercent / 100.0; }

    private static int clampByte(int value) { return Math.max(0, Math.min(255, value)); }
    private static int clampPercent(int value) { return Math.max(0, Math.min(100, value)); }
}
