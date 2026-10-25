package com.physicaldestroydeform;

import com.physicaldestroydeform.diagnostics.PddDiagnostics;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PddDiagnosticsTest {
    @Test
    void existingModelSmokeChecksPass() {
        assertTrue(PddDiagnostics.runModelSelfTest(LoggerFactory.getLogger(PddDiagnosticsTest.class)));
    }
}
