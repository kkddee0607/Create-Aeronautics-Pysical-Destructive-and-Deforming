package com.physicaldestroydeform.collision;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegionalContactProofTest {
    private static ContactCreditLedger.Region cell(int a,int b) {
        return new ContactCreditLedger.Region(
                new GameImpactCredit.Target("PLOT:one",a+",0,0","stone"),
                new GameImpactCredit.Target("WORLD:minecraft:overworld",b+",64,0","grass"));
    }
    @Test void comparesEveryVerifiedCellNotOnlyFirstOrForcePeak() {
        var before=RegionalContactProof.capture(List.of(cell(0,10),cell(1,11),cell(2,12)));
        assertFalse(RegionalContactProof.hasNewCells(before,List.of(cell(0,10),cell(1,11))));
        assertFalse(RegionalContactProof.hasNewCells(before,List.of(cell(2,12),cell(0,10))));
        assertTrue(RegionalContactProof.hasNewCells(before,List.of(cell(0,10),cell(2,12),cell(3,13))));
        assertFalse(RegionalContactProof.hasNewCells(null,List.of(cell(4,14))));
    }
    @Test void sameBlockStateChangeDoesNotFabricateRegionMovement() {
        var before=RegionalContactProof.capture(List.of(cell(0,10)));
        var changed=new ContactCreditLedger.Region(
                new GameImpactCredit.Target("PLOT:one","0,0,0","dirt"),
                new GameImpactCredit.Target("WORLD:minecraft:overworld","10,64,0","stone"));
        assertFalse(RegionalContactProof.hasNewCells(before,List.of(changed)));
    }
    @Test void largeFaceWithIdenticalFirstCellFindsRealNewTail() {
        var original=new java.util.ArrayList<ContactCreditLedger.Region>();
        for(int i=0;i<16_000;i++)original.add(cell(i,i));
        var before=RegionalContactProof.capture(original);
        assertFalse(RegionalContactProof.hasNewCells(before,original));
        var changed=new java.util.ArrayList<>(original);
        changed.add(cell(16_000,16_000));
        assertTrue(RegionalContactProof.hasNewCells(before,changed));
    }
}
