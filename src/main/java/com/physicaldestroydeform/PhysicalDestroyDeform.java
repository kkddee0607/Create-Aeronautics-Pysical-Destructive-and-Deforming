package com.physicaldestroydeform;

import net.neoforged.fml.common.Mod;

/**
 * PDD mod entry point.
 *
 * <p>Version 0.1.3 aligns the damage model with design draft 3.3.1. Runtime Sable collision
 * integration remains disabled until the documented API blockers are verified.</p>
 */
@Mod(PhysicalDestroyDeform.MOD_ID)
public final class PhysicalDestroyDeform {
    public static final String MOD_ID = "physical_destroy_deform";

    public PhysicalDestroyDeform() {
        // Runtime hooks are intentionally not registered until the exact Sable API is verified.
    }
}
