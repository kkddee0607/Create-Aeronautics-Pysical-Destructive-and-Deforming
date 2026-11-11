package com.physicaldestroydeform;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SelfElasticityDamageTest {
    private static final DamageModel.Settings SETTINGS=DamageModel.Settings.defaults();
    private static MaterialProperties material(int e) {
        return new MaterialProperties(0,1024,30,e,false);
    }

    @Test void fullElasticityOnlyRetainsTenPercentOfZeroElasticityDamage() {
        double q=100;
        var base=DamageModel.plan(q,material(0),new BlockDamageState(),SETTINGS);
        var elastic=DamageModel.plan(q,material(100),new BlockDamageState(),SETTINGS);
        assertEquals(base.rawDamage()*0.10,elastic.rawDamage(),1e-9);
        assertEquals(q,elastic.accountedQ(),1e-8);
        assertEquals(q*0.15,elastic.activeQ(),1e-8);
        assertEquals(q*0.15*DamageModel.DIRECT_SHARE,elastic.directQ(),1e-8);
        assertEquals(q*0.15*DamageModel.PLASTIC_SHARE,elastic.plasticQ(),1e-8);
    }

    @Test void strongerExistingElasticityReductionRemainsUnchanged() {
        var e50=DamageModel.plan(100,material(50),new BlockDamageState(),SETTINGS);
        var base=DamageModel.plan(100,material(0),new BlockDamageState(),SETTINGS);
        double oldFactor=DamageModel.elasticityTransmissionPercent(50,SETTINGS);
        assertEquals(oldFactor,DamageModel.ownDamageRetentionPercent(50,SETTINGS),1e-12);
        assertEquals(base.rawDamage()*oldFactor,e50.rawDamage(),1e-9);
    }

    @Test void selfDamageRetentionIsMonotoneAndNeverIncreasesPriorDamage() {
        double last=1;
        for(int e=0;e<=100;e++) {
            var p=DamageModel.planPreDamped(100,material(e),new BlockDamageState(),SETTINGS);
            double retention=DamageModel.ownDamageRetentionPercent(e,SETTINGS);
            assertTrue(retention<=last+1e-12);
            assertTrue(retention<=DamageModel.elasticityTransmissionPercent(e,SETTINGS)+1e-12);
            assertEquals(100,p.accountedQ(),1e-8);
            last=retention;
        }
    }

    @Test void callerPreviousStateAndPropagationBudgetRemainUntouched() {
        var previous=new BlockDamageState();
        previous.setDamage(15);
        var p=DamageModel.plan(100,material(100),previous,SETTINGS);
        assertEquals(15,previous.damage());
        assertEquals(100,p.accountedQ(),1e-8);
        assertTrue(p.fixedIncrement()>0);
    }
}
