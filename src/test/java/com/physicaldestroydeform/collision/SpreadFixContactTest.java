package com.physicaldestroydeform.collision;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpreadFixContactTest {
    @Test void sparseWitnessDoesNotAddHaloTargets(){
        var result=SableContactRegions.collect(OriginLastFixContactTest.PAIR,List.of(OriginLastFixContactTest.witness(.5)),
                (c,owner,remaining)->new SableContactRegions.Check(List.of(new GameImpactCredit.Target(owner,"0,0,0","minecraft:stone")),1));
        assertEquals(1,result.regions().size());
        assertEquals(result.regions(),SableContactRegions.mergeBilateralPairs(result.regions(),List.of()));
    }
    @Test void noVerifiedAnchorCannotSynthesizeContact(){
        var result=SableContactRegions.collect(OriginLastFixContactTest.PAIR,List.of(OriginLastFixContactTest.witness(.5)),
                (c,owner,remaining)->new SableContactRegions.Check(List.of(),0));
        assertFalse(result.verified());assertTrue(result.regions().isEmpty());
    }
    @Test void oneSidedShapeWithoutOppositeCannotCreateExtraTargets(){
        var a=OriginLastFixContactTest.target(0,0);
        var result=SableContactRegions.collect(OriginLastFixContactTest.PAIR,List.of(OriginLastFixContactTest.witness(.5)),
                (c,owner,remaining)->new SableContactRegions.Check(owner.equals(a.ownerKey())?List.of(a):List.of(),1));
        assertEquals(List.of(new ContactCreditLedger.Region(a,null)),result.regions());
        assertEquals(result.regions(),SableContactRegions.mergeBilateralPairs(result.regions(),List.of()));
    }
}
