package com.physicaldestroydeform.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ObservationKeyTest {
    @Test
    void keysWithSameTickSubstepAndBlockPairCompareEqual() {
        var first = new ObservationKey(12, 1, "minecraft:overworld",
                new CollisionObservation.BlockCoordinate(1, 2, 3),
                new CollisionObservation.BlockCoordinate(4, 5, 6));
        var second = new ObservationKey(12, 1, "minecraft:overworld",
                new CollisionObservation.BlockCoordinate(1, 2, 3),
                new CollisionObservation.BlockCoordinate(4, 5, 6));
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void missingOtherBlockRemainsAllowedForKey() {
        assertDoesNotThrow(() -> new ObservationKey(12, 1, "minecraft:overworld",
                new CollisionObservation.BlockCoordinate(1, 2, 3), null));
    }

    @Test
    void rejectsInvalidIdentityParts() {
        var block = new CollisionObservation.BlockCoordinate(1, 2, 3);
        assertThrows(IllegalArgumentException.class, () -> new ObservationKey(-1, 0, "level", block, null));
        assertThrows(IllegalArgumentException.class, () -> new ObservationKey(1, -1, "level", block, null));
        assertThrows(IllegalArgumentException.class, () -> new ObservationKey(1, 0, " ", block, null));
    }
}
