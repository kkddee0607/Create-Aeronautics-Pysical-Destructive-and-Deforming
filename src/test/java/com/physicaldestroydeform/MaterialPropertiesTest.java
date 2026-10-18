package com.physicaldestroydeform;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MaterialPropertiesTest {
    @Test
    void acceptsBoundaryValues() {
        assertDoesNotThrow(() -> new MaterialProperties(0, 0, 0));
        assertDoesNotThrow(() -> new MaterialProperties(255, 100, 100));
    }

    @Test
    void rejectsValuesOutsideStorageRanges() {
        assertThrows(IllegalArgumentException.class, () -> new MaterialProperties(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new MaterialProperties(256, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new MaterialProperties(0, 101, 0));
        assertThrows(IllegalArgumentException.class, () -> new MaterialProperties(0, 0, -1));
    }

    @Test
    void percentagesDecodeToFractions() {
        MaterialProperties material = new MaterialProperties(128, 75, 25);
        assertEquals(0.75, material.ductility(), 1e-9);
        assertEquals(0.25, material.elasticity(), 1e-9);
    }
}
