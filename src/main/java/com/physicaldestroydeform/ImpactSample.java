package com.physicaldestroydeform;

/** Immutable, API-independent impact input; this is not a Sable event type. */
public record ImpactSample(double impactEnergy, double normalRelativeSpeed, double effectiveMass) {
    public ImpactSample {
        requireFiniteNonNegative("impactEnergy", impactEnergy);
        requireFiniteNonNegative("normalRelativeSpeed", normalRelativeSpeed);
        requireFiniteNonNegative("effectiveMass", effectiveMass);
    }
    private static void requireFiniteNonNegative(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0) throw new IllegalArgumentException(name + " must be finite and >= 0; got " + value);
    }
    public boolean hasUsableImpact() { return impactEnergy > 0.0 && normalRelativeSpeed > 0.0; }
}
