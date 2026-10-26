package com.physicaldestroydeform;

/** Immutable block-type material; percentages are encoded as integers 0..100. */
public record MaterialProperties(int hardness, int ductilityPercent, int elasticityPercent) {
    public MaterialProperties {
        requireRange("hardness", hardness, 0, 255);
        requireRange("ductilityPercent", ductilityPercent, 0, 100);
        requireRange("elasticityPercent", elasticityPercent, 0, 100);
    }
    public double hardnessNormalized() { return hardness / 255.0; }
    public double ductility() { return ductilityPercent / 100.0; }
    public double elasticity() { return elasticityPercent / 100.0; }
    private static void requireRange(String name, int value, int min, int max) {
        if (value < min || value > max) throw new IllegalArgumentException(name + " must be in range " + min + ".." + max + "; got " + value);
    }
}
