package com.physicaldestroydeform.deform;

import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MoveSafetyTest {
    @Test void diagonalSweepsBothInterveningCellsBeforeDestination() {
        BlockPos source=new BlockPos(5,10,-5), target=new BlockPos(4,10,-4);
        assertEquals(List.of(new BlockPos(4,10,-5),new BlockPos(5,10,-4),target),
                MoveSafety.path(source,target));
    }
    @Test void cardinalAndInvalidPaths() {
        BlockPos source=new BlockPos(0,64,0), target=source.above();
        assertEquals(List.of(target),MoveSafety.path(source,target));
        assertThrows(IllegalArgumentException.class,()->MoveSafety.path(source,source));
        assertThrows(IllegalArgumentException.class,()->MoveSafety.path(source,new BlockPos(1,65,1)));
        assertThrows(IllegalArgumentException.class,()->MoveSafety.path(source,new BlockPos(0,66,0)));
    }
}
