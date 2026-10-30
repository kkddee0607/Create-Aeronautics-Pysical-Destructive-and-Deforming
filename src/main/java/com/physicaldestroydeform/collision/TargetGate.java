package com.physicaldestroydeform.collision;

/**
 * IDS 1.0.3 G-COL-02: target identity depends on independent live block audits,
 * never on the callback's contact-frame hypothesis or reciprocal matching.
 * This gate is diagnostic in 0.2.13: no result permits world mutation.
 */
public final class TargetGate {
    private TargetGate() {}

    public enum Outcome { BOTH_VERIFIED, HIT_ONLY, OTHER_ONLY, NEITHER_VERIFIED, NO_OTHER }

    public static Outcome evaluate(boolean hasOther, boolean hitVerified, boolean otherVerified) {
        if (!hasOther) return Outcome.NO_OTHER;
        if (hitVerified && otherVerified) return Outcome.BOTH_VERIFIED;
        if (hitVerified) return Outcome.HIT_ONLY;
        if (otherVerified) return Outcome.OTHER_ONLY;
        return Outcome.NEITHER_VERIFIED;
    }
}
