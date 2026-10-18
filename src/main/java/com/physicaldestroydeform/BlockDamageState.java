package com.physicaldestroydeform;

/**
 * Mutable per-block damage state.
 *
 * <p>Only create/store this state for blocks that have actually changed. Values are kept
 * as int in memory for simple arithmetic but are constrained to unsigned-byte ranges.</p>
 */
public final class BlockDamageState {
    public static final int MAX_VALUE = 255;
    private int damage;
    private int deformation;
    private int crackVisual;

    public int damage() { return damage; }
    public int deformation() { return deformation; }
    public int crackVisual() { return crackVisual; }

    public void addDamage(int amount) {
        damage = saturatingAdd(damage, amount);
    }

    public void addDeformation(int amount) {
        deformation = saturatingAdd(deformation, amount);
    }

    public void setDamage(int value) { damage = requireByteRange("damage", value); }
    public void setDeformation(int value) { deformation = requireByteRange("deformation", value); }
    public void setCrackVisual(int value) { crackVisual = requireByteRange("crackVisual", value); }

    public boolean isPristine() {
        return damage == 0 && deformation == 0 && crackVisual == 0;
    }

    /** Packs damage, deformation and crack intensity into the low 24 bits. */
    public int pack24() {
        return (damage & 0xFF) | ((deformation & 0xFF) << 8) | ((crackVisual & 0xFF) << 16);
    }

    /** Decodes the low 24 bits; high bits are ignored. */
    public static BlockDamageState unpack24(int packed) {
        BlockDamageState state = new BlockDamageState();
        state.damage = packed & 0xFF;
        state.deformation = (packed >>> 8) & 0xFF;
        state.crackVisual = (packed >>> 16) & 0xFF;
        return state;
    }

    private int saturatingAdd(int current, int amount) {
        if (amount <= 0) return current;
        return (int) Math.min(MAX_VALUE, (long) current + amount);
    }

    private static int requireByteRange(String name, int value) {
        if (value < 0 || value > MAX_VALUE) {
            throw new IllegalArgumentException(name + " must be in range 0..255; got " + value);
        }
        return value;
    }
}
