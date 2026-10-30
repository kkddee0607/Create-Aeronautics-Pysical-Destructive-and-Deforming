package com.physicaldestroydeform.collision;

import org.junit.jupiter.api.Test;
import net.minecraft.core.BlockPos;
import java.util.UUID;
import static com.physicaldestroydeform.collision.OwnerResolver.*;
import static com.physicaldestroydeform.collision.CollisionFrame.Space.*;
import static org.junit.jupiter.api.Assertions.*;

class OwnerResolverTest {
    @Test void utOwner02DifferentPlotIdsNeverAliasEvenWithSameCoordinates() {
        var a=new TargetKey("minecraft:overworld", SABLE_PLOT, UUID.randomUUID(),42,90,42,
                "minecraft:stone|state");
        var b=new TargetKey("minecraft:overworld", SABLE_PLOT, UUID.randomUUID(),42,90,42,
                "minecraft:stone|state");
        assertNotEquals(a,b);
    }
    @Test void utOwner01WorldAndPlotCoordinateKeysAreIndependent() {
        var world=new TargetKey("minecraft:overworld",STATIC_WORLD,null,78,64,-49,
                "minecraft:stone|state");
        var plot=new TargetKey("minecraft:overworld",SABLE_PLOT,UUID.randomUUID(),20481033,127,20483080,
                "aeronautics:levitite|state");
        assertNotEquals(world,plot);
        assertNotEquals(world.space(),plot.space());
    }
    @Test void embeddedAccessorRequiresCenterRelativeCoordinates() {
        BlockPos center=new BlockPos(20481034,128,20483080);
        BlockPos allocated=new BlockPos(20481033,129,20483081);
        BlockPos relative=OwnerResolver.toEmbeddedPosition(allocated,center);
        assertEquals(new BlockPos(-1,1,1),relative);
        assertEquals(allocated,relative.offset(center));
    }

    @Test void unsafeOwnerAndMissingFingerprintCannotBecomeMutationKeys() {
        assertThrows(IllegalArgumentException.class,()->new TargetKey("x",SABLE_PLOT,null,1,2,3,"x"));
        assertThrows(IllegalArgumentException.class,()->new TargetKey("x",STATIC_WORLD,UUID.randomUUID(),1,2,3,"x"));
        assertThrows(IllegalArgumentException.class,()->new TargetKey("x",STATIC_WORLD,null,1,2,3,""));
        assertFalse(new Check(Status.OWNER_UNRESOLVED,null,"unresolved").verified());
    }
}
