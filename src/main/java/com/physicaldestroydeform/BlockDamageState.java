package com.physicaldestroydeform;

/** Compact sparse value. Never allocate one per world block; mutation remains OFF in 0.4.0. */
public final class BlockDamageState {
    public static final int MAX_VALUE = 255;
    public static final int FIXED_SCALE = 256;
    private int damage;
    private int deformation;
    private int crackVisual;
    private int remainder;
    public int damage() { return damage; }
    public int deformation() { return deformation; }
    public int crackVisual() { return crackVisual; }
    public int remainder() { return remainder; }
    public long fixedDamage() { return damage * (long) FIXED_SCALE + remainder; }
    public void addDamage(int amount) { if (amount > 0) setDamage((int) Math.min(MAX_VALUE,(long)damage+amount)); }
    public void addDeformation(int amount) { if (amount > 0) setDeformation((int) Math.min(MAX_VALUE,(long)deformation+amount)); }
    public void setDamage(int value) { damage = requireByteRange("damage", value); }
    public void setDeformation(int value) { deformation = requireByteRange("deformation", value); }
    public void setCrackVisual(int value) { crackVisual = requireByteRange("crackVisual", value); }
    public void setRemainder(int value) { remainder = requireByteRange("remainder", value); }
    public void addFixedDamage(long increment) {
        if (increment < 0) throw new IllegalArgumentException("negative fixed increment");
        long next = Math.min(65535L, fixedDamage() + Math.min(increment, 65535L));
        damage = (int) (next / FIXED_SCALE);
        remainder = (int) (next % FIXED_SCALE);
    }
    public void addRawDamage(double rawDamage) {
        if (!Double.isFinite(rawDamage) || rawDamage < 0)
            throw new IllegalArgumentException("invalid raw damage");
        addFixedDamage((long) Math.floor(Math.min(65535.0, rawDamage * FIXED_SCALE)));
    }
    public boolean isPristine() { return damage==0 && deformation==0 && crackVisual==0 && remainder==0; }
    /** Historical three-field representation: remainder is serialized separately. */
    public int pack24() { return (damage & 255) | ((deformation & 255) << 8) | ((crackVisual & 255) << 16); }
    public BlockDamageState copy() {
        BlockDamageState other=unpack24(pack24());
        other.setRemainder(remainder);
        return other;
    }
    public static BlockDamageState unpack24(int packed) {
        if ((packed & 0xFF000000) != 0) throw new IllegalArgumentException("invalid packed damage bits");
        BlockDamageState state=new BlockDamageState();
        state.damage=packed & 255;
        state.deformation=(packed >>> 8) & 255;
        state.crackVisual=(packed >>> 16) & 255;
        return state;
    }
    private static int requireByteRange(String name, int value) {
        if (value<0 || value>255) throw new IllegalArgumentException(name+" must be in range 0..255; got "+value);
        return value;
    }
}
