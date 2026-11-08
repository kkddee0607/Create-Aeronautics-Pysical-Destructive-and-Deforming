package com.physicaldestroydeform;

/** IDS 1.0.6: immutable definition-level H/T/D/E profile; values are logical unsigned bytes. */
public record MaterialProperties(int hardness, int threshold, int ductilityPercent,
                                 int elasticityPercent, boolean unbreakable, double yieldThreshold) {
    public MaterialProperties {
        requireRange("hardness", hardness, 0, 255);
        requireRange("threshold", threshold, 1, 255);
        requireRange("ductilityPercent", ductilityPercent, 0, 100);
        requireRange("elasticityPercent", elasticityPercent, 0, 100);
        if (!Double.isFinite(yieldThreshold) || yieldThreshold < 0)
            throw new IllegalArgumentException("yieldThreshold must be finite and nonnegative");
    }

    /** Legacy three-argument callers retain their intended H/D/E ordering. */
    public MaterialProperties(int hardness, int ductilityPercent, int elasticityPercent) {
        this(hardness, defaultThreshold(hardness), ductilityPercent, elasticityPercent,
                false, defaultYield(ductilityPercent));
    }
    public MaterialProperties(int hardness, int threshold, int ductilityPercent,
                              int elasticityPercent, boolean unbreakable) {
        this(hardness, threshold, ductilityPercent, elasticityPercent, unbreakable,
                defaultYield(ductilityPercent));
    }
    public static int defaultThreshold(int hardness) {
        requireRange("hardness", hardness, 0, 255);
        return Math.max(1, Math.min(255, (int) Math.round(1 + 254 * Math.pow(hardness / 255.0, 0.65))));
    }
    public static double defaultYield(int ductilityPercent) {
        requireRange("ductilityPercent", ductilityPercent, 0, 100);
        return Math.max(0, 8 * (1 - ductilityPercent / 200.0));
    }
    /** Explicit World-only policy adapter; callers must not use this for Plot. */
    public MaterialProperties asBreakable() {
        return unbreakable
                ? new MaterialProperties(hardness,threshold,ductilityPercent,elasticityPercent,false,yieldThreshold)
                : this;
    }
    public double hardnessNormalized() { return hardness / 255.0; }
    public double ductility() { return ductilityPercent / 100.0; }
    public double elasticity() { return elasticityPercent / 100.0; }
    public int plasticCapacity() { return (int) Math.round(255 * ductility()); }

    private static void requireRange(String name, int value, int min, int max) {
        if (value < min || value > max)
            throw new IllegalArgumentException(name + " must be in range " + min + ".." + max + "; got " + value);
    }
}
