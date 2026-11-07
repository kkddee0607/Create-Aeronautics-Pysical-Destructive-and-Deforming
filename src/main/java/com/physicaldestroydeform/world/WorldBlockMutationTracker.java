package com.physicaldestroydeform.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Only registered damaged / pending world positions are observed; never scans the world. */
public final class WorldBlockMutationTracker {
    private static volatile WorldCommitService service;
    private WorldBlockMutationTracker() {}
    public static void attach(WorldCommitService next) { service=next; }
    public static void onSetBlockState(Level level,BlockPos pos,BlockState previous,BlockState next) {
        WorldCommitService active=service;
        if(active!=null&&!level.isClientSide() && previous!=null)
            active.beforeBlockReplacement(level,pos,previous,next);
    }
}
