package com.physicaldestroydeform;

/** Compact per-instance state. Values are unsigned-byte-compatible 0..255 integers. */
public final class BlockDamageState {
    private int damage;          // 0..255 accumulated damage
    private int deformation;     // 0..255 accumulated permanent deformation
    private int crackVisual;     // 0..255 visual crack intensity

    public int damage() { return damage; }
    public int deformation() { return deformation; }
    public int crackVisual() { return crackVisual; }

    public void addDamage(int amount) { damage = clampByte(damage + Math.max(0, amount)); }
    public void addDeformation(int amount) { deformation = clampByte(deformation + Math.max(0, amount)); }
    public void setCrackVisual(int value) { crackVisual = clampByte(value); }
    public boolean isPristine() { return damage == 0 && deformation == 0 && crackVisual == 0; }

    /** Encodes the three state bytes into a compact 24-bit value for packed storage. */
    public int pack24() {
        return (damage & 0xFF) | ((deformation & 0xFF) << 8) | ((crackVisual & 0xFF) << 16);
    }

    public static BlockDamageState unpack24(int packed) {
        BlockDamageState state = new BlockDamageState();
        state.damage = packed & 0xFF;
        state.deformation = (packed >>> 8) & 0xFF;
        state.crackVisual = (packed >>> 16) & 0xFF;
        return state;
    }

    private static int clampByte(int value) { return Math.max(0, Math.min(255, value)); }
}
