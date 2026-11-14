package com.physicaldestroydeform.plot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;

/** Synchronous, one-break witness of a real LevelChunk mutation. NOT a physics hook.
 * A success is never inferred from the Sable owner disappearing or an API boolean.
 */
public final class PlotBreakMutationWitness {
    private static final ThreadLocal<Pending> CURRENT=new ThreadLocal<>();
    private static final class Pending {
        final ServerLevel level;
        final BlockPos allocated;
        final BlockState expected;
        boolean changedToAir;
        LevelChunk changedChunk;
        Pending(ServerLevel level,BlockPos allocated,BlockState expected){
            this.level=level;this.allocated=allocated.immutable();this.expected=expected;
        }
    }
    private PlotBreakMutationWitness(){}
    public static void begin(ServerLevel level,BlockPos allocated,BlockState expected){
        if(CURRENT.get()!=null)throw new IllegalStateException("nested Plot break witness");
        if(level==null||allocated==null||expected==null||expected.isAir())
            throw new IllegalArgumentException("invalid Plot break target");
        CURRENT.set(new Pending(level,allocated,expected));
    }
    /** Called ONLY at RETURN of LevelChunk.setBlockState. No chunk loads. */
    public static void observe(Level level,BlockPos allocated,BlockState previous,
                               BlockState requested,LevelChunk changedChunk){
        Pending pending=CURRENT.get();
        if(pending==null || pending.changedToAir || level!=pending.level
                || !allocated.equals(pending.allocated) || !previous.equals(pending.expected)
                || !requested.isAir() || changedChunk==null
                || !changedChunk.getPos().equals(new net.minecraft.world.level.ChunkPos(allocated)))return;
        // Check the very same chunk we changed; do not resolve the potentially
        // removed Plot through SubLevelContainer, and never force a chunk load.
        try {
            if(changedChunk.getBlockState(allocated).isAir()
                    && changedChunk.getBlockEntity(allocated)==null){
                pending.changedChunk=changedChunk;
                pending.changedToAir=true;
            }
        }catch(RuntimeException|LinkageError unavailable){
            // Witness failure must not affect Sable's block-change callback.
        }
    }
    /** Always called in finally; no stale witness survives to the next ApplyId. */
    public static boolean end(){
        Pending pending=CURRENT.get();
        CURRENT.remove();
        if(pending==null || !pending.changedToAir || pending.changedChunk==null)return false;
        try {
            return pending.changedChunk.getBlockState(pending.allocated).isAir()
                    && pending.changedChunk.getBlockEntity(pending.allocated)==null;
        }catch(RuntimeException|LinkageError unavailable){return false;}
    }
}
