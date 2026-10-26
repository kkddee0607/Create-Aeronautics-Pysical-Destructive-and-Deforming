package com.physicaldestroydeform;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BlockDamageStateTest {
    @Test void unsignedByteRoundtripAndSaturation() {
        BlockDamageState state = new BlockDamageState();
        state.setDamage(255); state.setDeformation(128); state.setCrackVisual(17);
        BlockDamageState decoded = BlockDamageState.unpack24(state.pack24());
        assertEquals(255, decoded.damage()); assertEquals(128, decoded.deformation());
        assertEquals(17, decoded.crackVisual());
        decoded.addDamage(100); decoded.addDeformation(400);
        assertEquals(255, decoded.damage()); assertEquals(255, decoded.deformation());
    }
}
