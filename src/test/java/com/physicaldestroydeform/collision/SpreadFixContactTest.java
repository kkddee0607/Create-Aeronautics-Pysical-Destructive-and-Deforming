package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpreadFixContactTest {
    private static final String DIM="minecraft:overworld", OWNER="WORLD:"+DIM;
    private static GameImpactCredit.Target at(int x,int z) {
        return new GameImpactCredit.Target(OWNER,x+",64,"+z,"minecraft:stone");
    }
    private static NativeCollisionTelemetry.Contact witness(double x,double z) {
        return new NativeCollisionTelemetry.Contact(7,-1,3000,
                0,-1,0,0,1,0,0,0,0,x,65,z,DIM,99);
    }
    @Test void sparseWitnessAddsOnlyNearbyOwnerVerifiedCells() {
        var result=ContactFacePatch.reconstruct(DIM,OWNER,BlockPos.ZERO,
                List.of(at(0,0)),List.of(witness(.5,.5)),
                (pos,p)->pos.getX()>=0&&pos.getX()<=1&&pos.getZ()>=0&&pos.getZ()<=1
                        ?at(pos.getX(),pos.getZ()):null);
        assertTrue(result.reason().contains("sparseLocalHalos=1"));
        assertEquals(2,result.added().size());
        assertTrue(result.added().contains(at(1,0)));
        assertTrue(result.added().contains(at(0,1)));
        assertFalse(result.added().contains(at(1,1)));
        assertTrue(result.added().stream().noneMatch(t->t.localBlock().equals("2,64,2")));
    }
    @Test void noVerifiedAnchorCannotSynthesizeContact() {
        var result=ContactFacePatch.reconstruct(DIM,OWNER,BlockPos.ZERO,
                List.of(at(10,10)),List.of(witness(.5,.5)),(pos,p)->at(pos.getX(),pos.getZ()));
        assertTrue(result.added().isEmpty());
        assertEquals("NO_PROVEN_SURFACE",result.reason());
    }
    @Test void noShapeMeansNoAddedTargetsEvenWhenWitnessExists() {
        var result=ContactFacePatch.reconstruct(DIM,OWNER,BlockPos.ZERO,
                List.of(at(0,0)),List.of(witness(.5,.5)),(pos,p)->null);
        assertTrue(result.added().isEmpty());
    }
}
