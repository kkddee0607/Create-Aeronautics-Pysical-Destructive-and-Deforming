package com.physicaldestroydeform;

/**
 * Immutable, API-independent impact input for PDD's calculation pipeline.
 *
 * <p>This is not a Sable event type. An integration layer must translate verified
 * collision data into this value only after the target Sable API has been inspected.</p>
 *
 * @param impactEnergy nonnegative impact energy in the chosen consistent unit
 * @param normalRelativeSpeed nonnegative normal component of relative speed
 * @param effectiveMass nonnegative effective mass; zero means unavailable
 */
public record ImpactSample(
        double impactEnergy,
        double normalRelativeSpeed,
        double effectiveMass
) {
    public ImpactSample {
        requireFiniteNonNegative("impactEnergy", impactEnergy);
        requireFiniteNonNegative("normalRelativeSpeed", normalRelativeSpeed);
        requireFiniteNonNegative("effectiveMass", effectiveMass);
    }

    private static void requireFiniteNonNegative(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and >= 0; got " + value);
        }
    }

    public boolean hasUsableImpact() {
        return impactEnergy > 0.0 && normalRelativeSpeed > 0.0;
    }
}
