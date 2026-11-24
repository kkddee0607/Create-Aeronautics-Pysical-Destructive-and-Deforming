package com.physicaldestroydeform.collision;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ContactBudgetTopTest {
    private static ContactBudgetTop.Sample sample(int n,double input,double unresolved){
        return new ContactBudgetTop.Sample("credit-"+n,input,unresolved,"CONTACT_UNPROVED","PASS",false,"RESOLVED");
    }
    @Test void absoluteUnresolvedFIsLoggedEvenBelowTenPercent(){
        var audit=new ContactBudgetTop();audit.consider(sample(1,2_000_000,78_486.43));
        assertEquals(1,audit.samples().size());
        assertEquals(78_486.43,audit.samples().getFirst().unresolvedF(),0);
    }
    @Test void topFourNeverDropsLargestCreditsWhenTheyOccurLate(){
        var audit=new ContactBudgetTop();
        for(int n=1;n<=12;n++)audit.consider(sample(n,100_000,n*12_000));
        assertEquals(4,audit.samples().size());
        assertEquals("credit-12",audit.samples().getFirst().credit());
        assertEquals("credit-9",audit.samples().getLast().credit());
    }
    @Test void smallBudgetsWithoutHighRatioAndInvalidNumbersDoNotPolluteAudit(){
        var audit=new ContactBudgetTop();
        audit.consider(sample(1,100_000,500));
        audit.consider(sample(2,Double.NaN,100_000));
        audit.consider(sample(3,Double.POSITIVE_INFINITY,100_000));
        audit.consider(sample(4,0,100_000));
        audit.consider(sample(5,100,0));
        assertTrue(audit.samples().isEmpty());
        audit.consider(sample(6,100,15));
        assertEquals(1,audit.samples().size());
    }
}
