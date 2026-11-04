package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.BlockDamageState;
import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.MaterialProperties;
import com.physicaldestroydeform.material.MaterialProfiles;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The F values are hypothetical solver outputs, not measured Sable impulses. */
class ExpandedImpactScaleTest {
    @Test void smallAndLargeImpactsRemainDistinct() {
        var settings=GameImpactCredit.Settings.provisional();
        assertEquals(0.161,settings.forceGain(),1e-12);
        assertEquals(65536,settings.maxGroupQ(),0);
        assertEquals(41.216,settings.candidate(258),1e-9);
        assertEquals(64.078,settings.candidate(400),1e-9);
        assertEquals(6439.678,settings.candidate(40000),1e-9);
        assertTrue(settings.candidate(40000)>settings.candidate(400));
        assertTrue(settings.candidate(100000)>settings.candidate(40000));
        assertEquals(65536,settings.candidate(1e12),0);
        assertEquals(0,settings.candidate(Double.NaN),0);
    }
    @Test void theoreticalTenMetersPerSecondGrassThreshold() {
        var grass=MaterialProfiles.profile("minecraft:grass_block",new MaterialProperties(60,100,20,30,false));
        var settings=GameImpactCredit.Settings.provisional();
        var damage=DamageModel.Settings.defaults();
        assertEquals(2.6,damage.damageGain(),1e-12);
        var ten=DamageModel.plan(settings.candidate(400)/2,grass,new BlockDamageState(),damage);
        assertTrue(ten.thresholdReached(),"F=400 with one grass target per side must cross 32");
        assertTrue(ten.plannedDamageU8()>=32);
        var five=DamageModel.plan(settings.candidate(200)/2,grass,new BlockDamageState(),damage);
        assertFalse(five.thresholdReached());
        assertTrue(five.plannedDamageU8()<32);
    }
}
