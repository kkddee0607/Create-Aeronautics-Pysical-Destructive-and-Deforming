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
        assertEquals(0.1,settings.forceGain(),1e-12);
        assertEquals(0.1*(GameImpactCredit.MAX_GAME_F-2),settings.maxGroupQ(),1e-6);
        assertEquals(25.6,settings.candidate(258),1e-9);
        assertEquals(39.8,settings.candidate(400),1e-9);
        assertEquals(3999.8,settings.candidate(40000),1e-9);
        assertTrue(settings.candidate(40000)>settings.candidate(400));
        assertTrue(settings.candidate(100000)>settings.candidate(40000));
        assertEquals(settings.maxGroupQ(),settings.candidate(1e12),1e-6);
        assertEquals(0,settings.candidate(Double.NaN),0);
    }
    @Test void grassDamageThresholdRespectsReducedGain() {
        var grass=MaterialProfiles.profile("minecraft:grass_block",new MaterialProperties(60,100,20,30,false));
        var settings=GameImpactCredit.Settings.provisional();
        var damage=DamageModel.Settings.defaults();
        assertEquals(2.6,damage.damageGain(),1e-12);
        var stronger=DamageModel.plan(settings.candidate(800)/2,grass,new BlockDamageState(),damage);
        assertTrue(stronger.thresholdReached(),"F=800 with one grass target per side must cross 32 at k=0.1");
        assertTrue(stronger.plannedDamageU8()>=32);
        var weaker=DamageModel.plan(settings.candidate(400)/2,grass,new BlockDamageState(),damage);
        assertFalse(weaker.thresholdReached());
        assertTrue(weaker.plannedDamageU8()<32);
    }
}
