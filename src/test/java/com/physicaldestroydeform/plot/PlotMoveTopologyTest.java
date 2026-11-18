package com.physicaldestroydeform.plot;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PlotMoveTopologyTest {
    @Test void connectedCornerMayMoveWithoutSplitting(){
        var a=new BlockPos(0,0,0);var b=new BlockPos(1,0,0);
        var occupied=Set.of(a,new BlockPos(0,0,1),new BlockPos(1,0,1));
        assertEquals(PlotMoveTopology.Result.CONNECTED,PlotMoveTopology.check(a,b,occupied::contains));
    }
    @Test void isolatedDestinationIsDefinitelyBlocked(){
        var a=new BlockPos(0,0,0);var b=new BlockPos(1,0,0);
        assertEquals(PlotMoveTopology.Result.DISCONNECTED,
                PlotMoveTopology.check(a,b,Set.of(a,new BlockPos(-1,0,0))::contains));
    }
    @Test void unavailableNeighborCannotAuthorizeMovement(){
        var a=new BlockPos(0,0,0);var b=new BlockPos(1,0,0);
        assertEquals(PlotMoveTopology.Result.UNKNOWN,PlotMoveTopology.check(a,b,p->null));
    }
}
