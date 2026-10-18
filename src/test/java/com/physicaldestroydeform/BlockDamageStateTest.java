package com.physicaldestroydeform;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlockDamageStateTest {
    @Test
    void packAndUnpackPreservesAllThreeUnsignedBytes() {
        BlockDamageState state = new BlockDamageState();
        state.setDamage(255);
        state.setDeformation(128);
        state.setCrackVisual(17);

        BlockDamageState decoded = BlockDamageState.unpack24(state.pack24());
        assertEquals(255, decoded.damage());
        assertEquals(128, decoded.deformation());
        assertEquals(17, decoded.crackVisual());
    }

    @Test
    void accumulatedValuesSaturateAt255() {
        BlockDamageState state = new BlockDamageState();
        state.addDamage(250);
        state.addDamage(20);
        state.addDeformation(300);
        assertEquals(255, state.damage());
        assertEquals(255, state.deformation());
    }

    @Test
    void rejectsInvalidExplicitStateValues() {
        BlockDamageState state = new BlockDamageState();
        assertThrows(IllegalArgumentException.class, () -> state.setDamage(256));
        assertThrows(IllegalArgumentException.class, () -> state.setCrackVisual(-1));
    }
}
