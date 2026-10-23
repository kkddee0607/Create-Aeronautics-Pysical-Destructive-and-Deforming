package com.physicaldestroydeform;

import net.neoforged.fml.common.Mod;

/**
 * PDD mod entry point.
 *
 * <p>Version 0.2.0 records and tests API-verification boundaries. Runtime Sable collision
 * integration remains disabled until callback semantics and safe processing are verified.</p>
 */
@Mod(PhysicalDestroyDeform.MOD_ID)
public final class PhysicalDestroyDeform {
    public static final String MOD_ID = "physical_destroy_deform";

    public PhysicalDestroyDeform() {
        // Runtime hooks remain disabled until the API gates documented for 0.2.0 are verified.
    }
}
