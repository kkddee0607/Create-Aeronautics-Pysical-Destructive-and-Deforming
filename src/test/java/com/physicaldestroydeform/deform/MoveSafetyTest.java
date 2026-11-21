package com.physicaldestroydeform.deform;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import com.physicaldestroydeform.deform.MoveTransactionExecutor.Gate;
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
    @Test void onlyExactKnownGlueClassAndRegistryPairQualifies() {
        String create="com.simibubi.create.content.contraptions.glue.SuperGlueEntity";
        String honey="dev.simulated_team.simulated.content.entities.honey_glue.HoneyGlueEntity";
        assertTrue(MoveSafety.isAssemblyGlue(create,"create:super_glue"));
        assertTrue(MoveSafety.isAssemblyGlue(honey,"simulated:honey_glue"));
        assertFalse(MoveSafety.isAssemblyGlue(create,"simulated:honey_glue"));
        assertFalse(MoveSafety.isAssemblyGlue("other.FakeGlue","create:super_glue"));
        assertFalse(MoveSafety.isAssemblyGlue(honey,"other:honey_glue"));
        assertFalse(MoveSafety.isAssemblyGlue(null,null));
    }
    @Test void retainsMetadataOnlyForProvenSamePlot() {
        assertEquals(Gate.APPROVED,MoveSafety.plotEntityGate(true,true));
        assertEquals(Gate.UNKNOWN,MoveSafety.plotEntityGate(true,false));
        assertEquals(Gate.BLOCKED,MoveSafety.plotEntityGate(false,true));
        assertEquals(Gate.BLOCKED,MoveSafety.plotEntityGate(false,false));
    }
    @Test void glueRegionMustStayWithinOneAllocationIncludingAnchor() {
        AABB whole=new AABB(-32,0,-16,-16,2,0);
        assertTrue(MoveSafety.glueWithinPlot(whole,-24,-8,c->c.x==-2&&c.z==-1));
        assertFalse(MoveSafety.glueWithinPlot(whole,-15,-8,c->c.x==-2&&c.z==-1));
        assertFalse(MoveSafety.glueWithinPlot(new AABB(-32,0,-16,-15.5,2,0),
                -24,-8,c->c.x==-2&&c.z==-1));
        assertFalse(MoveSafety.glueWithinPlot(new AABB(-32,0,-16,-16,2,0.5),
                -24,-8,c->c.x==-2&&c.z==-1));
    }
    @Test void malformedGlueRegionIsNotTrusted() {
        assertFalse(MoveSafety.glueWithinPlot(new AABB(0,0,0,0,1,1),0,0,c->true));
        assertFalse(MoveSafety.glueWithinPlot(new AABB(0,0,0,1,1,1),Double.NaN,0,c->true));
        assertFalse(MoveSafety.glueWithinPlot(new AABB(0,0,0,Double.POSITIVE_INFINITY,1,1),0,0,c->true));
        assertFalse(MoveSafety.glueWithinPlot(new AABB(0,0,0,1,1,1),0,0,c->false));
    }
}
