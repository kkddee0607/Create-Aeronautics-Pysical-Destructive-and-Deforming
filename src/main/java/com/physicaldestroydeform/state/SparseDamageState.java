package com.physicaldestroydeform.state;

import com.physicaldestroydeform.BlockDamageState;
import java.util.Objects;

/** Versioned *serializable value*; no on-disk game state is written by PDD 0.4.0. */
public record SparseDamageState(SparseStateKey key, int damageU16, int deformationU8,
                                int crackVisualU8, int remainderU8,
                                long materialGeneration, long lastUpdateTick) {
    public SparseDamageState {
        Objects.requireNonNull(key);
        if(damageU16<0||damageU16>BlockDamageState.MAX_VALUE)
            throw new IllegalArgumentException("invalid U16 damage");byteValue(deformationU8);byteValue(crackVisualU8);byteValue(remainderU8);
        if(materialGeneration<0||lastUpdateTick<0)throw new IllegalArgumentException("invalid sparse metadata");
        if(damageU16==0&&deformationU8==0&&crackVisualU8==0&&remainderU8==0)
            throw new IllegalArgumentException("zero-state must not be stored");
    }
    public static SparseDamageState from(SparseStateKey key,BlockDamageState state,
                                         long materialGeneration,long tick) {
        return new SparseDamageState(key,state.damage(),state.deformation(),state.crackVisual(),
                state.remainder(),materialGeneration,tick);
    }
    public BlockDamageState unpack() {
        BlockDamageState s=new BlockDamageState();
        s.setDamage(damageU16);s.setDeformation(deformationU8);s.setCrackVisual(crackVisualU8);
        s.setRemainder(remainderU8);return s;
    }
    private static void byteValue(int v){if(v<0||v>255)throw new IllegalArgumentException("invalid unsigned byte");}
}
