package com.physicaldestroydeform.material;

/** IDS 1.0.6 §7.3: Minecraft mining/blast values are gameplay policy inputs, not SI hardness. */
public final class HardnessScale {
    private HardnessScale() {}
    public static int fromMinecraft(double destroySpeed,double explosionResistance) {
        return fromMinecraft(destroySpeed,explosionResistance,0);
    }
    /** toolEvidence 0..1 is permitted only for a verified actual tool-tier tag. */
    public static int fromMinecraft(double destroySpeed,double explosionResistance,double toolEvidence) {
        if (Double.isFinite(destroySpeed) && destroySpeed<0) return 255; // separate UNBREAKABLE flag kept in MaterialProperties
        if (!Double.isFinite(toolEvidence) || toolEvidence<0 || toolEvidence>1)
            throw new IllegalArgumentException("invalid tool evidence");
        double mining=fraction(destroySpeed,55);
        double blast=fraction(explosionResistance,1200);
        return (int)Math.round(255*(0.60*mining+0.35*blast+0.05*toolEvidence));
    }
    private static double fraction(double value,double reference) {
        if (Double.isNaN(value) || value<=0) return 0;
        if (!Double.isFinite(value)) return 0;
        return Math.min(1,Math.log1p(value)/Math.log1p(reference));
    }
}
