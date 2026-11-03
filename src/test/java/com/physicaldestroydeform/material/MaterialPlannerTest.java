package com.physicaldestroydeform.material;

import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.MaterialProperties;
import com.physicaldestroydeform.collision.ContactCreditLedger;
import com.physicaldestroydeform.collision.GameImpactCredit;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MaterialPlannerTest {
    private static final String PLOT="PLOT:5069c055-4ebd-415c-b205-a6715b83156d";
    private static final String WORLD="WORLD:minecraft:overworld";
    private static ContactCreditLedger.Grant issued() {
        var pair=GameImpactCredit.Pair.canonical("run", "minecraft:overworld",
                new GameImpactCredit.Owner(PLOT,2),new GameImpactCredit.Owner(WORLD,1));
        var plot=new GameImpactCredit.Target(PLOT,"1,2,3","minecraft:reinforced_deepslate|Block{minecraft:reinforced_deepslate}");
        var world=new GameImpactCredit.Target(WORLD,"10,70,-10","minecraft:stone|Block{minecraft:stone}");
        return new ContactCreditLedger.Grant(new ContactCreditLedger.CreditId(pair,7),
                ContactCreditLedger.Source.SABLE_POST_EFFECT,ContactCreditLedger.Status.ISSUED,
                100,40,List.of(new ContactCreditLedger.Share(plot,20)),
                List.of(new ContactCreditLedger.Share(world,20)),0,2,
                ContactCreditLedger.ALGORITHM_REVISION);
    }
    @Test void issuedSharesArePlannedOnceWithNoWorldCommit() {
        var report=MaterialPlanner.plan(issued(),3,fp->
                fp.startsWith("minecraft:reinforced")?new MaterialProperties(255,255,10,20,false):
                        new MaterialProperties(10,30,10,15,false),DamageModel.Settings.defaults());
        assertEquals(2,report.plans().size());
        assertEquals(40,report.accountedQ(),1e-9);
        assertEquals(40,report.plannedQ(),1e-9);
        assertEquals(0,report.missingMaterialQ());
        assertTrue(report.fixedIncrementTotal()>0);
        assertTrue(report.plans().stream().allMatch(p->p.direction().isEmpty()));
        assertTrue(report.plans().stream().allMatch(p->p.plan().status()==DamageModel.Status.PLANNED_NOT_APPLIED));
        assertEquals(3,report.materialGeneration());
        assertEquals(2,report.plans().getFirst().key().ownerGeneration());
    }
    @Test void unavailableMaterialIsNotReallocatedToTheOtherSide() {
        var r=MaterialPlanner.plan(issued(),6,fp->
                fp.startsWith("minecraft:stone")?new MaterialProperties(20,10,10):null,
                DamageModel.Settings.defaults());
        assertEquals(1,r.plans().size());
        assertEquals(20,r.missingMaterialQ());
        assertEquals(20,r.plannedQ());
        assertEquals(40,r.accountedQ(),1e-9);
    }
    @Test void refusesUnapprovedCredit() {
        var good=issued();
        var bad=new ContactCreditLedger.Grant(good.id(),good.source(),ContactCreditLedger.Status.DUPLICATE_CAUSE,
                good.tick(),0,List.of(),List.of(),0,0,good.algorithmRevision());
        assertThrows(IllegalArgumentException.class,
                ()->MaterialPlanner.plan(bad,1,fp->new MaterialProperties(10,10,10),DamageModel.Settings.defaults()));
    }
}
