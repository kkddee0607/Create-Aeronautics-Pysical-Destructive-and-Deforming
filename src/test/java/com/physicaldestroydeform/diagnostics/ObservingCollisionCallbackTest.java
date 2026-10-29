package com.physicaldestroydeform.diagnostics;

import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;
import net.minecraft.core.BlockPos;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies once-only original invocation and response identity on both Sable API methods. */
class ObservingCollisionCallbackTest {
    @Test void originalNativeArrayMustBeReturnedByIdentity() {
        SableContactQueue.drain();
        AtomicInteger calls = new AtomicInteger();
        double[] originalArray = {1.0, 2.0, 3.0, 1.0};
        BlockSubLevelCollisionCallback original = new BlockSubLevelCollisionCallback() {
            @Override public double[] onCollision(int x, int y, int z, int ox, int oy, int oz,
                                                  double ix, double iy, double iz, double v, boolean other) {
                calls.incrementAndGet();
                return originalArray;
            }
            @Override public CollisionResult sable$onCollision(BlockPos hit, BlockPos other, Vector3d pos, double velocity) {
                fail("The JNI path must not invoke sable$onCollision again");
                return CollisionResult.NONE;
            }
        };
        var decorator = new ObservingCollisionCallback(original, "minecraft:stone");
        assertSame(originalArray, decorator.onCollision(1, 2, 3, 4, 5, 6, 7, 8, 9, -10, true));
        assertEquals(1, calls.get());
        var records = SableContactQueue.drain();
        assertEquals(1, records.size());
        assertEquals(-10, records.getFirst().signedVelocity());
        assertTrue(records.getFirst().removeCollision());
        assertTrue(records.getFirst().hadOriginal());
    }

    @Test void originalDirectResultMustBeReturnedByIdentity() {
        SableContactQueue.drain();
        AtomicInteger calls = new AtomicInteger();
        var originalResult = new BlockSubLevelCollisionCallback.CollisionResult(new Vector3d(0.5, 0, 0), true);
        BlockSubLevelCollisionCallback original = new BlockSubLevelCollisionCallback() {
            @Override public CollisionResult sable$onCollision(BlockPos hit, BlockPos other, Vector3d pos, double velocity) {
                calls.incrementAndGet();
                return originalResult;
            }
        };
        var decorator = new ObservingCollisionCallback(original, "minecraft:tnt");
        var result = decorator.sable$onCollision(new BlockPos(1, 2, 3), null, new Vector3d(1, 2, 3), -2);
        assertSame(originalResult, result);
        assertEquals(1, calls.get());
        var records = SableContactQueue.drain();
        assertEquals(1, records.size());
        assertEquals(RawObservation.SOURCE_DIRECT_CALLBACK, records.getFirst().source());
        assertTrue(records.getFirst().removeCollision());
    }

    @Test void absentOriginalIsNeutralButCapturesOrdinaryBlock() {
        SableContactQueue.drain();
        var decorator = new ObservingCollisionCallback(null, "minecraft:oak_planks");
        assertArrayEquals(new double[]{0, 0, 0, 0},
                decorator.onCollision(10, 64, 10, 0, 0, 0, 0, 0, 0, -0.5, false));
        var records = SableContactQueue.drain();
        assertEquals(1, records.size());
        assertEquals("minecraft:oak_planks", records.getFirst().blockId());
        assertFalse(records.getFirst().hadOriginal());
        assertFalse(records.getFirst().removeCollision());
    }
}
