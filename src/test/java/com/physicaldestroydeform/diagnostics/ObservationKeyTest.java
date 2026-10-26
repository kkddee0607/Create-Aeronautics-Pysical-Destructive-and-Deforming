package com.physicaldestroydeform.diagnostics;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ObservationKeyTest {
    @Test void keyEquality() {
        var pos = new CollisionObservation.BlockCoordinate(1, 2, 3);
        assertEquals(new ObservationKey(1, 2, "minecraft:overworld", pos, null),
                new ObservationKey(1, 2, "minecraft:overworld", pos, null));
    }
}
