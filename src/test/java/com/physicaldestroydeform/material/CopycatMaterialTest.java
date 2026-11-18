package com.physicaldestroydeform.material;

import com.physicaldestroydeform.MaterialProperties;
import java.util.List;
import java.util.BitSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CopycatMaterialTest {
    private static final MaterialProperties IRON=new MaterialProperties(112,546,70,35,false);
    private static final MaterialProperties OAK=new MaterialProperties(59,265,35,25,false);
    private static final MaterialProperties OBSIDIAN=new MaterialProperties(239,986,0,0,false);
    @Test void halfSlabAndSingleByteScaleOnlyThreshold(){
        assertEquals(new MaterialProperties(239,493,0,0,false),CopycatMaterial.mix(List.of(new CopycatMaterial.Part(OBSIDIAN,.5))));
        assertEquals(new MaterialProperties(239,123,0,0,false),CopycatMaterial.mix(List.of(new CopycatMaterial.Part(OBSIDIAN,.125))));
    }
    @Test void doubleSlabUsesFinalRoundAndActualWeights(){
        assertEquals(new MaterialProperties(86,406,53,30,false),CopycatMaterial.mix(List.of(new CopycatMaterial.Part(IRON,.5),new CopycatMaterial.Part(OAK,.5))));
    }
    @Test void impossibleVolumeAndNonfiniteWeightsAreRejected(){
        assertThrows(IllegalArgumentException.class,()->new CopycatMaterial.Part(IRON,Double.NaN));
        assertThrows(IllegalArgumentException.class,()->CopycatMaterial.mix(List.of(new CopycatMaterial.Part(IRON,.75),new CopycatMaterial.Part(OAK,.5))));
        assertThrows(IllegalArgumentException.class,()->CopycatMaterial.mix(List.of()));
    }
    @Test void boardOverlapSharesEachOccupiedCellExactlyOnce(){
        BitSet a=new BitSet(),b=new BitSet();a.set(0,4);b.set(2,6);
        BitSet union=(BitSet)a.clone();union.or(b);
        assertArrayEquals(new double[]{.375,.375},CopycatMaterial.unionWeights(new BitSet[]{a,b},union,8),1e-12);
        assertNull(CopycatMaterial.unionWeights(new BitSet[]{a,b},a,8));
    }
    @Test void layersAndFixedSlotsRetainExactActiveVolume(){
        for(int i=1;i<=8;i++)assertEquals(i/8.0,CopycatMaterial.layerWeight(i));
        assertEquals(0,CopycatMaterial.layerWeight(0));
        assertEquals(0,CopycatMaterial.layerWeight(9));
        assertArrayEquals(new double[]{.5,.5},CopycatMaterial.fixedWeights(2,2),0);
        assertEquals(1,java.util.Arrays.stream(CopycatMaterial.fixedWeights(8,8)).sum());
        assertNull(CopycatMaterial.fixedWeights(9,8));
    }
    @Test void moveAllowlistNeverIncludesFunctionalCopycats(){
        String be="com.copycatsplus.copycats.foundation.copycat.CCCopycatBlockEntity";
        assertTrue(CopycatMaterial.safeDecorative("copycat_stairs",be));
        assertFalse(CopycatMaterial.safeDecorative("copycat_door",be));
        assertFalse(CopycatMaterial.safeDecorative("copycat_shaft",be));
        assertFalse(CopycatMaterial.safeDecorative("copycat_block","unknown.CustomBE"));
    }
    @Test void normalDefaultMaterialIsNotRecursiveCopycat(){
        assertTrue(CopycatMaterial.referenceAllowed("create:copycat_base|Block{create:copycat_base}"));
        assertTrue(CopycatMaterial.referenceAllowed("minecraft:iron_block|Block{minecraft:iron_block}"));
        assertFalse(CopycatMaterial.referenceAllowed("copycats:copycat_slab|Block{copycats:copycat_slab}"));
        assertFalse(CopycatMaterial.referenceAllowed("create:copycat_panel|Block{create:copycat_panel}"));
        assertFalse(CopycatMaterial.referenceAllowed(null));
    }
}
