package com.physicaldestroydeform.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.physicaldestroydeform.diagnostics.RogTrace.Stage.*;

class RogTraceTest {
    @Test void zeroCapCountsWithoutDetails(){
        var s=new RogTrace.State("s",0,0,1,100);s.begin(1);
        assertFalse(s.sample(R04,"GAP"));assertEquals(1,s.seen(R04,"GAP"));assertEquals(0,s.selected(R04,"GAP"));
    }
    @Test void globalAndStageCapsResetOnTick(){
        var s=new RogTrace.State("s",3,2,8,100);s.begin(1);
        assertTrue(s.sample(R04,"A"));assertTrue(s.sample(R04,"A"));assertFalse(s.sample(R04,"B"));
        assertTrue(s.sample(R09,"B"));assertFalse(s.sample(R10,"C"));
        s.begin(1);assertFalse(s.sample(R10,"C"));s.begin(2);assertTrue(s.sample(R10,"C"));
        assertEquals(3,s.seen(R10,"C"));assertEquals(1,s.selected(R10,"C"));
    }
    @Test void normalSampleDoesNotStarvePeriodicEvidence(){
        var s=new RogTrace.State("s",128,32,1,100);s.begin(1);
        assertTrue(s.sample(R09,"HOLD"));assertFalse(s.sample(R09,"HOLD"));s.begin(100);assertFalse(s.sample(R09,"HOLD"));
        s.begin(101);assertTrue(s.sample(R09,"HOLD"));assertEquals(4,s.seen(R09,"HOLD"));assertEquals(2,s.selected(R09,"HOLD"));
    }
    @Test void debugRepeatIsBounded(){
        var s=new RogTrace.State("s",128,32,8,100);s.begin(1);
        for(int i=0;i<8;i++)assertTrue(s.sample(R03,"NORMAL"));assertFalse(s.sample(R03,"NORMAL"));
        s.begin(2);assertTrue(s.sample(R03,"NORMAL"));assertEquals(9,s.selected(R03,"NORMAL"));
    }
    @Test void unknownReasonsHaveBoundedMemory(){
        var s=new RogTrace.State("s",0,0,1,100);s.begin(1);
        for(int i=0;i<1000;i++)s.sample(R11,"REASON_"+i);
        assertEquals(64,s.counts.get(R11).size());assertEquals(937,s.seen(R11,"OTHER_REASONS"));
    }
    @Test void contextAndCountersDoNotLeakAcrossSessions(){
        var a=new RogTrace.State("a",1,1,1,100);a.begin(1);a.dimension="world";a.pair="pair";a.nativeTick=99;
        a.sample(R02,"OWNER");a.begin(2);assertNull(a.dimension);assertNull(a.pair);assertEquals(-1,a.nativeTick);
        var b=new RogTrace.State("b",1,1,1,100);b.begin(1);assertEquals(0,b.seen(R02,"OWNER"));assertTrue(b.sample(R02,"OWNER"));
    }
    @Test void clientAndServerBudgetsAreIndependent(){
        var a=new RogTrace.State("server",1,1,1,100);var b=new RogTrace.State("client",1,1,1,100);
        a.begin(1);b.begin(1);assertTrue(a.sample(R12,"MISSING"));assertTrue(b.sample(R12,"MISSING"));
        assertFalse(a.sample(R12,"MISSING"));assertEquals(1,b.seen(R12,"MISSING"));
    }
    @Test void summaryDistinguishesSeenSelectedAndSuppressed(){
        var s=new RogTrace.State("s",1,1,1,100);s.begin(1);s.sample(R01,"CAP");s.sample(R01,"CAP");
        assertEquals("{CAP:2/1/1}",s.summary(R01));assertTrue(s.due());s.lastSummary=1;s.begin(100);assertFalse(s.due());s.begin(101);assertTrue(s.due());
    }
    @Test void missingSnapshotsCountBeyondFormerThirtyTwoLimit(){
        var s=new RogTrace.State("client",32,32,8,10);s.begin(1);
        for(int i=0;i<100;i++)s.sample(R12,"SNAPSHOT_MISSING");
        assertEquals(100,s.seen(R12,"SNAPSHOT_MISSING"));
        assertEquals(8,s.selected(R12,"SNAPSHOT_MISSING"));
        s.begin(2);assertTrue(s.sample(R12,"SNAPSHOT_MISSING"));
        assertEquals(101,s.seen(R12,"SNAPSHOT_MISSING"));
    }
    @Test void aggregateEventsShareGlobalCapAndKeepSuppressionCount(){
        var s=new RogTrace.State("s",4,4,8,100,2);s.begin(1);
        assertTrue(s.sample(R04,"DETAIL"));assertTrue(s.sample(R04,"DETAIL"));
        assertFalse(s.sample(R04,"DETAIL"));
        assertTrue(s.event(R06,"F_CREDIT_SUMMARY"));assertTrue(s.event(R06,"F_CREDIT_SUMMARY"));
        assertFalse(s.event(R06,"F_CREDIT_SUMMARY"));
        assertEquals(3,s.seen(R06,"F_CREDIT_SUMMARY"));assertEquals(2,s.selected(R06,"F_CREDIT_SUMMARY"));
        s.begin(2);assertTrue(s.event(R06,"F_CREDIT_SUMMARY"));
    }
    @Test void selectedEvidenceCannotSpendAggregateReserve(){
        var s=new RogTrace.State("s",4,4,8,100,2);s.begin(1);
        assertTrue(s.evidence(R04,"SEED"));assertTrue(s.evidence(R04,"SEED"));assertFalse(s.evidence(R04,"SEED"));
        assertTrue(s.event(R06,"F_CREDIT_SUMMARY"));assertTrue(s.event(R06,"CONTACT_SIDE_SEAL"));
        assertEquals(3,s.seen(R04,"SEED"));assertEquals(2,s.selected(R04,"SEED"));
    }
    @Test void aggregateEventCapZeroStillCounts(){
        var s=new RogTrace.State("s",0,0,1,100,0);s.begin(1);
        assertFalse(s.event(R06,"SEAL"));assertEquals(1,s.seen(R06,"SEAL"));
        assertEquals(0,s.selected(R06,"SEAL"));
    }
}
