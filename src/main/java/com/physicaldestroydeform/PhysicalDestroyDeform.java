package com.physicaldestroydeform;

import com.physicaldestroydeform.diagnostics.PddDiagnostics;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** PDD debug build entry point. Diagnostics exercise in-memory models only. */
@Mod(PhysicalDestroyDeform.MOD_ID)
public final class PhysicalDestroyDeform {
    public static final String MOD_ID = "physical_destroy_deform";
    public static final String BUILD_VERSION = "0.2.0-debug";
    private static final Logger LOGGER = LoggerFactory.getLogger(PhysicalDestroyDeform.class);

    public PhysicalDestroyDeform() {
        LOGGER.info("[PDD-DIAG] INIT modId={} version={} status=LOADED", MOD_ID, BUILD_VERSION);
        boolean passed = PddDiagnostics.runModelSelfTest(LOGGER);
        LOGGER.info("[PDD-DIAG] RUNTIME_INTEGRATION sableCallback=NOT_IMPLEMENTED " +
                "materialRegistry=NOT_IMPLEMENTED worldMutation=DISABLED " +
                "destruction=DISABLED deformation=DISABLED visuals=DISABLED");
        LOGGER.info("[PDD-DIAG] INIT_DIAGNOSTIC status={}", passed ? "PASS" : "FAIL");
    }
}
