package com.physicaldestroydeform;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MaterialPropertiesTest {
    @Test void bounds() {
        assertDoesNotThrow(() -> new MaterialProperties(0, 0, 0));
        assertDoesNotThrow(() -> new MaterialProperties(255, 100, 100));
        assertThrows(IllegalArgumentException.class, () -> new MaterialProperties(256, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new MaterialProperties(0, 101, 0));
    }
    @Test void percentages() {
        assertEquals(0.75, new MaterialProperties(0, 75, 25).ductility());
        assertEquals(0.25, new MaterialProperties(0, 75, 25).elasticity());
    }
}
