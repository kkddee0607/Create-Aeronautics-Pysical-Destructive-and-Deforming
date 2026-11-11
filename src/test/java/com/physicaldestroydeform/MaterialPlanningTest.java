package com.physicaldestroydeform;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MaterialPlanningTest {
    private static final DamageModel.Settings POLICY=DamageModel.Settings.defaults();
    @Test void hardnessMonotonicallyResistsAndH255IsNotInvulnerable() {
        double prior=Double.POSITIVE_INFINITY;
        for(int h=0;h<=255;h++) {
            var material=new MaterialProperties(h,200,30,0,false);
            var plan=DamageModel.plan(64,material,new BlockDamageState(),POLICY);
            assertTrue(plan.rawDamage()<=prior+1e-10);
            prior=plan.rawDamage();
        }
        assertTrue(prior>0);
    }
    @Test void elasticityAppliedExactlyOnceThenUnusedBudgetNotRedistributed() {
        var m=new MaterialProperties(0,200,100,100,false);
        var plan=DamageModel.plan(40,m,new BlockDamageState(),POLICY);
        assertEquals(6,plan.activeQ(),1e-9);
        assertEquals(34,plan.absorbedQ(),1e-9);
        assertEquals(3.6,plan.directQ(),1e-9);
        assertEquals(.9,plan.plasticQ(),1e-9);
        assertEquals(1.5,plan.stressQ(),1e-9);
        assertEquals(2.4,plan.unusedPlannedQ(),1e-9);
        assertEquals(40,plan.accountedQ(),1e-9);
        assertEquals(3.6*POLICY.damageGain()*(0.10/0.15),plan.rawDamage(),1e-9);
        assertEquals(0,plan.plannedPlasticIncrement()); // yield not reached: no invented deformation
    }
    @Test void plasticCapacityAndYieldAreIndependentOfDirectDamage() {
        var ductile=new MaterialProperties(20,200,100,0,false,1);
        var brittle=new MaterialProperties(20,200,0,0,false,1);
        var a=DamageModel.plan(40,ductile,new BlockDamageState(),POLICY);
        var b=DamageModel.plan(40,brittle,new BlockDamageState(),POLICY);
        assertEquals(a.rawDamage(),b.rawDamage(),1e-9);
        assertTrue(a.plannedPlasticIncrement()>0);
        assertEquals(0,b.plannedPlasticIncrement());
        assertEquals(0,a.plannedDamageU8()-b.plannedDamageU8());
    }
    @Test void unbreakableVetoDoesNotConsumeOrRedistribute() {
        var m=new MaterialProperties(255,255,0,0,true);
        var plan=DamageModel.plan(60,m,new BlockDamageState(),POLICY);
        assertEquals(DamageModel.Status.UNBREAKABLE_UNUSED,plan.status());
        assertEquals(60,plan.unusedPlannedQ());
        assertEquals(0,plan.rawDamage());
        assertEquals(60,plan.accountedQ());
    }
    @Test void tinyDamageAggregatesWithoutPerImpactCeiling() {
        var m=new MaterialProperties(255,255,0,0,false);
        BlockDamageState current=new BlockDamageState();
        var p=DamageModel.plan(.1,m,current,POLICY); // 0.1 * 0.6 * 0.08 * 2.6 = .01248
        assertEquals(3,p.fixedIncrement());
        assertEquals(0,p.plannedDamageU8());
        for(int i=0;i<256;i++)current.addFixedDamage(p.fixedIncrement());
        assertEquals(3,current.damage());assertEquals(0,current.remainder());
    }
    @Test void refusesInvalidEnergyAndParameters() {
        assertThrows(IllegalArgumentException.class,
                ()->DamageModel.plan(Double.NaN,new MaterialProperties(0,0,0),new BlockDamageState(),POLICY));
        assertThrows(IllegalArgumentException.class,
                ()->DamageModel.plan(-1,new MaterialProperties(0,0,0),new BlockDamageState(),POLICY));
        assertThrows(IllegalArgumentException.class,
                ()->new DamageModel.Settings(0,1,.15,1,1,1));
    }
    @Test void thresholdAndCurrentDamageAreIndependent() {
        var m=new MaterialProperties(0,7,0,0,false);
        var state=new BlockDamageState();state.setDamage(6);state.setRemainder(250);
        var p=DamageModel.plan(1,m,state,POLICY);
        assertEquals(8,p.plannedDamageU8());assertTrue(p.thresholdReached());
        assertEquals(6,state.damage()); // a *plan* never commits the old mutable state
    }
    @Test void materialPlanZeroDoesNotChangePrevious() {
        var state=new BlockDamageState();state.setDamage(5);state.setRemainder(9);
        var plan=DamageModel.plan(0,new MaterialProperties(0,0,0),state,POLICY);
        assertEquals(5,plan.plannedDamageU8());assertEquals(9,plan.plannedRemainderU8());
        assertEquals(DamageModel.Status.ZERO_Q,plan.status());
    }
}
