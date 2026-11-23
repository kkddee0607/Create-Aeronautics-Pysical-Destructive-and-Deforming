package com.physicaldestroydeform.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class OptiFix2PhaseMetricsTest {
    @Test void allPhasesBeginEmpty() {
        var d=new RuntimeDiagnostics();for(var phase:RuntimeDiagnostics.Phase.values())
            assertEquals(0,d.phaseSnapshot(phase).count());
    }
    @Test void phaseTimingsRemainSeparated() {
        var d=new RuntimeDiagnostics();d.recordPhase(RuntimeDiagnostics.Phase.BRIDGE,10);
        d.recordPhase(RuntimeDiagnostics.Phase.WORLD_COMMIT,20);
        assertEquals(10,d.phaseSnapshot(RuntimeDiagnostics.Phase.BRIDGE).p99());
        assertEquals(20,d.phaseSnapshot(RuntimeDiagnostics.Phase.WORLD_COMMIT).max());
        assertEquals(0,d.phaseSnapshot(RuntimeDiagnostics.Phase.STRESS).count());
    }
    @Test void emptyAndInvalidDurationsDoNotIntroduceArtificialCost() {
        var d=new RuntimeDiagnostics();d.recordPhase(RuntimeDiagnostics.Phase.CAPTURE,-2);
        assertEquals(0,d.phaseSnapshot(RuntimeDiagnostics.Phase.CAPTURE).count());
        d.recordPhase(RuntimeDiagnostics.Phase.CAPTURE,0);
        assertEquals(1,d.phaseSnapshot(RuntimeDiagnostics.Phase.CAPTURE).count());
    }
    @Test void phaseMaximumDoesNotDisappearAfterWindowWrap() {
        var d=new RuntimeDiagnostics();d.recordPhase(RuntimeDiagnostics.Phase.WORLD_COMMIT,44_518_000_000L);
        for(int i=0;i<600;i++)d.recordPhase(RuntimeDiagnostics.Phase.WORLD_COMMIT,1);
        var p=d.phaseSnapshot(RuntimeDiagnostics.Phase.WORLD_COMMIT);
        assertEquals(512,p.count());assertEquals(1,p.p99());assertEquals(44_518_000_000L,p.max());
    }
    @Test void resetPurgesPreviousSessionHighWaterAndCounts() {
        var d=new RuntimeDiagnostics();d.recordPhase(RuntimeDiagnostics.Phase.DISPATCH,123);
        d.reset(System.nanoTime());assertEquals(0,d.phaseSnapshot(RuntimeDiagnostics.Phase.DISPATCH).count());
        assertEquals(0,d.phaseSnapshot(RuntimeDiagnostics.Phase.DISPATCH).max());
    }
}
