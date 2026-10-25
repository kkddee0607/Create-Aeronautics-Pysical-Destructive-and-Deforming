package com.physicaldestroydeform.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ObservationKeyTest {
    @Test void keysWithSameTickSubstepAndBlockPairCompareEqual() {
        var hit = new CollisionObservation.BlockCoordinate(1, 2, 3);
        var other = new CollisionObservation.BlockCoordinate(4, 5, 6);
        var first = new ObservationKey(12, 1, "minecraft:overworld", hit, other);
        var second = new ObservationKey(12, 1, "minecraft:overworld", hit, other);
        assertEquals(first, second); assertEquals(first.hashCode(), second.hashCode());
    }
    @Test void missingOtherBlockIsAllowedForKey() {
        assertDoesNotThrow(() -> new ObservationKey(12, 1, "minecraft:overworld",
                new CollisionObservation.BlockCoordinate(1, 2, 3), null));
    }
    @Test void rejectsInvalidIdentityParts() {
        var block = new CollisionObservation.BlockCoordinate(1, 2, 3);
        assertThrows(IllegalArgumentException.class, () -> new ObservationKey(-1, 0, "level", block, null));
        assertThrows(IllegalArgumentException.class, () -> new ObservationKey(1, -1, "level", block, null));
        assertThrows(IllegalArgumentException.class, () -> new ObservationKey(1, 0, " ", block, null));
    }
}
