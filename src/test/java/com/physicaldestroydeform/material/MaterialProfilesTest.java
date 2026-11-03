package com.physicaldestroydeform.material;

import com.physicaldestroydeform.MaterialProperties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MaterialProfilesTest {
    private static final MaterialProperties BASE=new MaterialProperties(60,100,20,30,false);
    @Test void exactVanillaAnchors() {
        var deep=MaterialProfiles.profile("minecraft:reinforced_deepslate",BASE);
        assertEquals(255,deep.hardness());assertEquals(255,deep.threshold());
        assertEquals(0,MaterialProfiles.profile("minecraft:obsidian",BASE).elasticityPercent());
        assertEquals(100,MaterialProfiles.profile("minecraft:slime_block",BASE).elasticityPercent());
        assertEquals(100,MaterialProfiles.profile("minecraft:gold_block",BASE).ductilityPercent());
    }
    @Test void concreteProfilesDifferEvenIfFamilyBothMetal() {
        var iron=MaterialProfiles.profile("minecraft:iron_block",BASE);
        var copper=MaterialProfiles.profile("minecraft:copper_block",BASE);
        assertNotEquals(iron.ductilityPercent(),copper.ductilityPercent());
        assertNotEquals(iron.elasticityPercent(),copper.elasticityPercent());
        assertEquals(60,iron.hardness());
    }
    @Test void unknownModdedNameNeverBecomesTrustedProfile() {
        assertNull(MaterialProfiles.profile("madeup:super_steel_wheel",BASE));
        assertFalse(MaterialProfiles.explicit("madeup:super_steel_wheel"));
    }
    @Test void unbreakableFlagCannotBeMadeBreakableByAnchor() {
        var b=new MaterialProperties(255,255,0,0,true);
        assertTrue(MaterialProfiles.profile("minecraft:reinforced_deepslate",b).unbreakable());
    }
}
