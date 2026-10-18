package com.physicaldestroydeform;

import net.neoforged.fml.common.Mod;

/** Main mod entry point. Gameplay hooks are intentionally not registered in 0.1. */
@Mod(PhysicalDestroyDeform.MOD_ID)
public final class PhysicalDestroyDeform {
    public static final String MOD_ID = "physical_destroy_deform";

    public PhysicalDestroyDeform() {
        // Version 0.1 establishes the compact material/damage model first.
        // Sable/Aeronautics event integration must be added against the exact installed API.
    }
}
