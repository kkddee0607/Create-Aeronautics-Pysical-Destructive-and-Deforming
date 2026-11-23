package com.physicaldestroydeform.material;

import com.physicaldestroydeform.MaterialProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CopycatCalculationCacheTest {
    private static final MaterialProperties IRON=new MaterialProperties(112,546,70,35,false);
    private static final MaterialProperties OAK=new MaterialProperties(59,265,35,25,false);
    private static final List<CopycatMaterial.Part> HALF_IRON=List.of(new CopycatMaterial.Part(IRON,.5));
    private static final String IRON_SLOT="CC-3.0.11/PDD-SHAPE-2|copycats:copycat_slope|Block{copycats:copycat_slope}|single=minecraft:iron_block|Block{minecraft:iron_block}@0.5";

    @Test void immutableIdenticalInputsReuseCalculatedCapture(){
        var cache=new CopycatMaterial.CalculationCache(2);
        var first=cache.resolve(1,IRON_SLOT,HALF_IRON,true);
        assertSame(first,cache.resolve(1,new String(IRON_SLOT),List.copyOf(HALF_IRON),true));
        assertEquals(1,cache.stats().hits());assertEquals(1,cache.stats().misses());
    }

    @Test void preservesOriginalHashAndMixExactly() throws Exception {
        var cache=new CopycatMaterial.CalculationCache(2);
        var capture=cache.resolve(1,IRON_SLOT,HALF_IRON,true);
        assertEquals(CopycatMaterial.mix(HALF_IRON),capture.resolved().material());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(IRON_SLOT.getBytes(StandardCharsets.UTF_8))),capture.resolved().revision());
        assertEquals(IRON_SLOT,capture.slots());assertEquals(.5,capture.k());assertEquals("OK",capture.reason());
    }

    @Test void differentMaterialGenerationNeverReusesOldCapture(){
        var cache=new CopycatMaterial.CalculationCache(2);
        assertNotSame(cache.resolve(1,IRON_SLOT,HALF_IRON,true),cache.resolve(2,IRON_SLOT,HALF_IRON,true));
        assertEquals(0,cache.stats().hits());
    }

    @Test void actualMaterialPropertiesRemainPartOfExactIdentity(){
        var cache=new CopycatMaterial.CalculationCache(2);
        var previous=cache.resolve(1,IRON_SLOT,HALF_IRON,true);
        var changed=cache.resolve(1,IRON_SLOT,List.of(new CopycatMaterial.Part(OAK,.5)),true);
        assertNotEquals(previous.resolved().material(),changed.resolved().material());
    }

    @Test void slotSignatureChangePreservesNewRevisionEvenIfMixMatches(){
        var cache=new CopycatMaterial.CalculationCache(2);
        var previous=cache.resolve(1,IRON_SLOT,HALF_IRON,true);
        var changed=cache.resolve(1,IRON_SLOT+"|changed",HALF_IRON,true);
        assertEquals(previous.resolved().material(),changed.resolved().material());
        assertNotEquals(previous.resolved().revision(),changed.resolved().revision());
    }

    @Test void exactWeightChangeChangesThreshold(){
        var cache=new CopycatMaterial.CalculationCache(2);
        var previous=cache.resolve(1,IRON_SLOT,HALF_IRON,true);
        var changed=cache.resolve(1,IRON_SLOT,List.of(new CopycatMaterial.Part(IRON,.25)),true);
        assertEquals(273,previous.resolved().material().threshold());
        assertEquals(137,changed.resolved().material().threshold());
    }

    @Test void safeDecorativePolicyIsNeverSharedWithOtherBeClasses(){
        var cache=new CopycatMaterial.CalculationCache(2);
        assertTrue(cache.resolve(1,IRON_SLOT,HALF_IRON,true).resolved().safeDecorativeBE());
        assertFalse(cache.resolve(1,IRON_SLOT,HALF_IRON,false).resolved().safeDecorativeBE());
    }

    @Test void lruEvictionOnlyRecomputesSameValue(){
        var cache=new CopycatMaterial.CalculationCache(2);
        var removed=cache.resolve(1,IRON_SLOT,HALF_IRON,true);
        cache.resolve(1,"second",HALF_IRON,true);cache.resolve(1,"third",HALF_IRON,true);
        assertEquals(2,cache.stats().entries());assertEquals(1,cache.stats().evictions());
        var restored=cache.resolve(1,IRON_SLOT,HALF_IRON,true);
        assertEquals(removed,restored);assertNotSame(removed,restored);
    }

    @Test void cacheHitRefreshesLruOrder(){
        var cache=new CopycatMaterial.CalculationCache(2);
        var first=cache.resolve(1,IRON_SLOT,HALF_IRON,true);
        cache.resolve(1,"second",HALF_IRON,true);cache.resolve(1,IRON_SLOT,HALF_IRON,true);
        cache.resolve(1,"third",HALF_IRON,true);assertSame(first,cache.resolve(1,IRON_SLOT,HALF_IRON,true));
    }

    @Test void invalidPartUnionNeverEntersCache(){
        var cache=new CopycatMaterial.CalculationCache(2);
        assertThrows(IllegalArgumentException.class,()->cache.resolve(1,"invalid",List.of(new CopycatMaterial.Part(IRON,.75),new CopycatMaterial.Part(OAK,.75)),false));
        assertEquals(0,cache.stats().entries());
    }

    @Test void reloadInvalidationDropsValuesButPreservesCounters(){
        var cache=new CopycatMaterial.CalculationCache(2);
        var previous=cache.resolve(1,IRON_SLOT,HALF_IRON,true);cache.clear();
        assertEquals(0,cache.stats().entries());assertEquals(1,cache.stats().misses());assertEquals(1,cache.stats().invalidations());
        assertNotSame(previous,cache.resolve(1,IRON_SLOT,HALF_IRON,true));
    }

    @Test void sessionResetAlsoResetsCounters(){
        var cache=new CopycatMaterial.CalculationCache(2);
        cache.resolve(1,IRON_SLOT,HALF_IRON,true);cache.clear();cache.reset();
        assertEquals(new CopycatMaterial.CalculationStats(0,0,0,0,0),cache.stats());
    }
}
