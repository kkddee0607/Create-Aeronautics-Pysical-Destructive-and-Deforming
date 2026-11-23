package com.physicaldestroydeform.diagnostics;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OptiDiagnosticsTest {
    private static RawObservation sample() {
        return new RawObservation(1,"callback","UNKNOWN","UNVERIFIED","minecraft:stone",
                "NONE",true,0,0,0,false,0,0,0,0,0,0,1,false);
    }
    @Test void secondSessionHasFreshRegisteredCounters() {
        var report=new AcceptanceReport();
        try {
            RawObservationDispatcher.dispatch(List.of(sample()));
            assertEquals(1,report.counts().snapshot().original());
            report.close();
            RawObservationDispatcher.dispatch(List.of(sample()));
            assertEquals(1,report.counts().snapshot().original());
            report.start();
            assertEquals(0,report.counts().snapshot().original());
            RawObservationDispatcher.dispatch(List.of(sample(),sample()));
            assertEquals(2,report.counts().snapshot().original());
        } finally {report.close();}
    }
    @Test void diagnosticModeParsingIsExplicitAndSafe() {
        assertEquals(DiagnosticMode.NORMAL,DiagnosticMode.parse(null));
        assertEquals(DiagnosticMode.NORMAL,DiagnosticMode.parse("invalid"));
        assertEquals(DiagnosticMode.DEBUG,DiagnosticMode.parse(" debug "));
        assertEquals(DiagnosticMode.TRACE,DiagnosticMode.parse("TRACE"));
    }
    @Test void fixedWindowEvictsOldSamplesAndComputesPercentiles() {
        var window=new RuntimeDiagnostics.Window(4);
        for(long i=1;i<=6;i++)window.record(i);
        var p=window.snapshot();
        assertEquals(4,p.count());assertEquals(4,p.p50());
        assertEquals(6,p.p95());assertEquals(6,p.p99());
        window.reset();assertEquals(0,window.snapshot().count());
    }
    @Test void invalidDurationDoesNotPolluteWindow() {
        var window=new RuntimeDiagnostics.Window(3);
        window.record(-1);window.record(0);window.record(10);
        assertEquals(2,window.snapshot().count());assertEquals(0,window.snapshot().p50());
        assertEquals(10,window.snapshot().p99());
    }
    @Test void reportingIntervalDoesNotAccumulateUnboundedWork() {
        var d=new RuntimeDiagnostics();d.reset(10);
        assertFalse(d.reportDue(10));
        assertFalse(d.reportDue(10+RuntimeDiagnostics.INTERVAL_NS-1));
        assertTrue(d.reportDue(10+RuntimeDiagnostics.INTERVAL_NS));
        assertFalse(d.reportDue(10+RuntimeDiagnostics.INTERVAL_NS));
        assertTrue(d.reportDue(10+4*RuntimeDiagnostics.INTERVAL_NS));
    }
}
