package com.physicaldestroydeform.material;

import com.physicaldestroydeform.BlockDamageState;
import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.MaterialProperties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ColFix2ElasticMaterialTest {
    @Test void slimeSelfAbsorbsAndHasIndependentThreshold() {
        var slime=MaterialProfiles.profile("minecraft:slime_block",new MaterialProperties(0,1,30,0,false));
        assertEquals(100,slime.elasticityPercent());
        assertEquals(64,slime.threshold());
        var p=DamageModel.planPreDamped(56.06225,slime,new BlockDamageState(),DamageModel.Settings.defaults());
        assertEquals(56.06225,p.accountedQ(),1e-8);
        assertEquals(56.06225*.15,p.activeQ(),1e-8);
        assertFalse(p.thresholdReached());
        assertTrue(p.absorbedQ()>0);
    }
}
