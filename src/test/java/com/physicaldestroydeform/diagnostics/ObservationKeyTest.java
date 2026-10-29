package com.physicaldestroydeform.diagnostics;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ObservationKeyTest {
    @Test void basicValueKey() {
        var coordinate = new CollisionObservation.BlockCoordinate(0, 1, 0);
        var a = new ObservationKey(1, 2, "minecraft:overworld", coordinate, null);
        var b = new ObservationKey(1, 2, "minecraft:overworld", coordinate, null);
        assertEquals(a, b);
    }
}
