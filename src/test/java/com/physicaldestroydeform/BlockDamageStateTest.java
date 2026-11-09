package com.physicaldestroydeform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BlockDamageStateTest {
    @Test
    void legacyUnsignedByteRoundtripPreservesFieldsAndExtendedDamage() {
        BlockDamageState state = new BlockDamageState();
        state.setDamage(255);
        state.setDeformation(128);
        state.setCrackVisual(17);

        BlockDamageState decoded = BlockDamageState.unpack24(state.pack24());
        assertEquals(255, decoded.damage());
        assertEquals(128, decoded.deformation());
        assertEquals(17, decoded.crackVisual());

        // T1024: accumulating beyond the legacy U8 boundary must not saturate at 255.
        decoded.addDamage(100);
        decoded.addDeformation(400);
        assertEquals(355, decoded.damage());
        assertEquals(255, decoded.deformation());

        // The legacy packing path must reject, rather than truncate, extended damage.
        assertThrows(IllegalStateException.class, decoded::pack24);

        decoded.addDamage(1_000);
        assertEquals(BlockDamageState.MAX_VALUE, decoded.damage());
        decoded.addDamage(1);
        assertEquals(BlockDamageState.MAX_VALUE, decoded.damage());
    }

    @Test
    void extendedDamageBoundariesAndLegacyEncodingGuard() {
        BlockDamageState state = new BlockDamageState();
        state.setDamage(256);
        assertEquals(256, state.damage());
        assertThrows(IllegalStateException.class, state::pack24);

        state.setDamage(1_023);
        state.addDamage(1);
        assertEquals(1_024, state.damage());
        assertEquals(1_024L * BlockDamageState.FIXED_SCALE, state.fixedDamage());
        assertThrows(IllegalArgumentException.class, () -> state.setDamage(1_025));
        assertThrows(IllegalArgumentException.class, () -> state.setDamage(-1));
    }
}
