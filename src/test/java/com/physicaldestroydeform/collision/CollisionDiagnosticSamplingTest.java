package com.physicaldestroydeform.collision;

import org.junit.jupiter.api.Test;
import static com.physicaldestroydeform.collision.GameImpactCredit.Reason.*;
import static com.physicaldestroydeform.collision.SableContactRegions.Status.*;
import static org.junit.jupiter.api.Assertions.*;

final class CollisionDiagnosticSamplingTest {
    @Test void previewModeDefaultsToSummaryAndSupportsExplicitFullOrOff() {
        var summary=RealFixRuntimeBridge.PreviewMode.SUMMARY;
        assertEquals(summary,RealFixRuntimeBridge.PreviewMode.parse("summary"));
        assertEquals(summary,RealFixRuntimeBridge.PreviewMode.parse(""));
        assertEquals(summary,RealFixRuntimeBridge.PreviewMode.parse("invalid"));
        assertEquals(RealFixRuntimeBridge.PreviewMode.FULL,RealFixRuntimeBridge.PreviewMode.parse(" Full "));
        assertEquals(RealFixRuntimeBridge.PreviewMode.OFF,RealFixRuntimeBridge.PreviewMode.parse("off"));
    }
    @Test void restingDuplicateLogsFirstAndThenOnePerHundredTicks() {
        var trace=new RealFixRuntimeBridge.RejectionTrace();
        int shows=0;
        for(long tick=100;tick<=399;tick++)
            if(trace.sample(false,false,tick,DUPLICATE_EPISODE,SKIPPED_DUPLICATE,100))shows++;
        assertEquals(3,shows); // ticks 100, 200, 300
    }
    @Test void newReasonAndAuditStatusAreImmediatelyVisible() {
        var trace=new RealFixRuntimeBridge.RejectionTrace();
        assertTrue(trace.sample(false,false,100,DUPLICATE_EPISODE,SKIPPED_DUPLICATE,100));
        assertFalse(trace.sample(false,false,101,DUPLICATE_EPISODE,SKIPPED_DUPLICATE,100));
        assertTrue(trace.sample(false,false,102,TARGET_UNVERIFIED,SKIPPED_DUPLICATE,100));
        assertTrue(trace.sample(false,false,103,TARGET_UNVERIFIED,NO_VERIFIED_REGION,100));
    }
    @Test void approvedAuditAndFullTraceBypassRepeatSampling() {
        var trace=new RealFixRuntimeBridge.RejectionTrace();
        assertTrue(trace.sample(false,false,100,DUPLICATE_EPISODE,SKIPPED_DUPLICATE,100));
        assertTrue(trace.sample(true,false,100,DUPLICATE_EPISODE,SKIPPED_DUPLICATE,100));
        assertTrue(trace.sample(false,true,100,DUPLICATE_EPISODE,SKIPPED_DUPLICATE,100));
        assertTrue(trace.sample(false,true,100,APPROVED,PROVED,100));
        assertTrue(trace.sample(false,true,100,APPROVED,PROVED,100));
    }
}
