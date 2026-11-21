package com.physicaldestroydeform.plot;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    @Test void thinWallCanDentOneCellIntoAirWhileRetainingEdgeConnections(){
        var a=new BlockPos(-3,0,0);var b=new BlockPos(-3,0,-1);
        var occupied=Set.of(a,a.east(),a.west(),a.above(),a.below());
        assertEquals(PlotMoveTopology.Result.CONNECTED,PlotMoveTopology.check(a,b,occupied::contains));
    }
    @Test void allTwelveEdgeDirectionsAreConnections(){
        var a=new BlockPos(0,0,0);
        for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++){
            if(Math.abs(x)+Math.abs(y)+Math.abs(z)!=2)continue;
            var edge=a.offset(x,y,z);
            var b=a.offset(x,0,0);
            if(x==0)b=a.offset(0,y,0);
            assertEquals(PlotMoveTopology.Result.CONNECTED,PlotMoveTopology.check(a,b,Set.of(a,edge)::contains));
        }
    }
    @Test void threeAxisCornerDoesNotConnectDestination(){
        var a=new BlockPos(0,0,0);var b=new BlockPos(1,0,0);
        assertEquals(PlotMoveTopology.Result.DISCONNECTED,
                PlotMoveTopology.check(a,b,Set.of(a,new BlockPos(0,1,1))::contains));
    }
    @Test void severedEdgeBranchCannotBeApproved(){
        var a=new BlockPos(0,0,0);var b=new BlockPos(1,0,0);
        var occupied=Set.of(a,new BlockPos(0,1,0),new BlockPos(-1,-1,0));
        assertEquals(PlotMoveTopology.Result.UNKNOWN,PlotMoveTopology.check(a,b,occupied::contains));
    }
    @Test void unavailableEdgeNeighborCannotAuthorizeMovement(){
        var a=new BlockPos(0,0,0);var b=new BlockPos(1,0,0);
        var unknown=new BlockPos(-1,1,0);
        assertEquals(PlotMoveTopology.Result.UNKNOWN,
                PlotMoveTopology.check(a,b,p->p.equals(unknown)?null:p.equals(a.above())));
    }
    @Test void repeatedTopologyProbesReadEachBoundedCellOnlyOnce(){
        var a=new BlockPos(0,0,0);var b=new BlockPos(1,0,0);
        var occupied=Set.of(a,new BlockPos(0,1,0),new BlockPos(0,2,0),new BlockPos(-1,-1,0));
        var calls=new HashMap<BlockPos,Integer>();
        assertEquals(PlotMoveTopology.Result.UNKNOWN,PlotMoveTopology.check(a,b,p->{
            calls.merge(p,1,Integer::sum);
            return occupied.contains(p);
        }));
        assertTrue(calls.size()<=125);
        assertTrue(calls.values().stream().allMatch(n->n==1));
    }
}
