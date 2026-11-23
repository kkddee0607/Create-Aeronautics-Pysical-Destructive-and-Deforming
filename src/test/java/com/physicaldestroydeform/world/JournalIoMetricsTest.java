package com.physicaldestroydeform.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class JournalIoMetricsTest {
    @Test void emptySummaryIsEmpty(){assertEquals("",new JournalIoMetrics().summary());}
    @Test void negativeSamplesAreIgnored(){
        var m=new JournalIoMetrics();m.record(JournalIoMetrics.Phase.WAL_FORCE,-3);
        assertEquals("",m.summary());
    }
    @Test void eachPhaseIsSeparated(){
        var m=new JournalIoMetrics();m.record(JournalIoMetrics.Phase.WAL_APPEND,7);
        m.record(JournalIoMetrics.Phase.CHECKPOINT,23);
        var s=m.summary();assertTrue(s.contains("WAL_APPEND={count:1,p95Nanos:7,maxNanos:7}"));
        assertTrue(s.contains("CHECKPOINT={count:1,p95Nanos:23,maxNanos:23}"));
        assertFalse(s.contains("WAL_FORCE"));
    }
    @Test void maximumSurvivesRingEviction(){
        var m=new JournalIoMetrics();m.record(JournalIoMetrics.Phase.WAL_FORCE,44_518_000_000L);
        for(int i=0;i<130;i++)m.record(JournalIoMetrics.Phase.WAL_FORCE,1);
        var s=m.summary();assertTrue(s.contains("count:131,p95Nanos:1,maxNanos:44518000000"),s);
    }
    @Test void rollingPercentileTracksSlowSamples(){
        var m=new JournalIoMetrics();for(int i=1;i<=100;i++)m.record(JournalIoMetrics.Phase.COLD_RECEIPT_READ,i);
        assertTrue(m.summary().contains("p95Nanos:95,maxNanos:100"));
    }
}
