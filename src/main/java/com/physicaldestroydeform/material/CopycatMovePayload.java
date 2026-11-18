package com.physicaldestroydeform.material;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Full metadata, components and material payload for verified decorative Copycats+ BEs.
 * Call only after owner, loaded chunk, server thread and mutation authorization checks.
 * The caller must use UPDATE_MOVE_BY_PISTON for forward and rollback setBlock operations:
 * Copycats+ 3.0.11 onRemove skips consumed item drops iff isMoving is true.
 */
public final class CopycatMovePayload {
    private static final int MAX_PAYLOAD=8192;
    private static final long MAX_NBT=1L<<20;
    private CopycatMovePayload(){}

    static CompoundTag positionIndependent(CompoundTag tag){
        CompoundTag data=tag.copy();data.remove("x");data.remove("y");data.remove("z");return data;
    }
    static String encode(CompoundTag data){
        try{
            var out=new ByteArrayOutputStream();NbtIo.writeCompressed(data,out);
            String value=Base64.getEncoder().encodeToString(out.toByteArray());
            if(value.length()>MAX_PAYLOAD||!data.equals(decode(value)))return null;
            return value;
        }catch(IOException|RuntimeException invalid){return null;}
    }
    static CompoundTag decode(String payload){
        if(payload==null||payload.isEmpty()||payload.length()>MAX_PAYLOAD)return null;
        try{return NbtIo.readCompressed(new ByteArrayInputStream(Base64.getDecoder().decode(payload)),NbtAccounter.create(MAX_NBT));}
        catch(IOException|RuntimeException invalid){return null;}
    }
    private static BlockEntity allowed(BlockGetter level,BlockPos pos,BlockState state){
        // Waterlogged blocks may have fluid placement/removal effects outside the move journal.
        if(!state.getFluidState().isEmpty()||!state.equals(level.getBlockState(pos)))return null;
        BlockEntity be=level.getBlockEntity(pos);return CopycatMaterial.safeDecorative(state,be)?be:null;
    }
    /** Returns null for absent or unsafe BE, oversized/unreadable data or unsupported version. */
    public static String capture(BlockGetter level,BlockPos pos,BlockState state,HolderLookup.Provider provider){
        try{
            BlockEntity be=allowed(level,pos,state);if(be==null)return null;
            return encode(positionIndependent(be.saveWithFullMetadata(provider)));
        }catch(RuntimeException|LinkageError invalid){return null;}
    }
    public static boolean matches(BlockGetter level,BlockPos pos,BlockState state,HolderLookup.Provider provider,String payload){
        try{
            var data=decode(payload);BlockEntity be=allowed(level,pos,state);
            return data!=null&&be!=null&&data.equals(positionIndependent(be.saveWithFullMetadata(provider)));
        }catch(RuntimeException|LinkageError invalid){return false;}
    }
    /** Verify type before loading, rebase metadata to actual BE position (including Plot offset), then exact readback.
     * A false result is a failed mutation; the caller's durable journal must roll back or quarantine. */
    public static boolean restore(BlockGetter level,BlockPos pos,BlockState state,HolderLookup.Provider provider,String payload){
        try{
            CompoundTag data=decode(payload);BlockEntity be=allowed(level,pos,state);
            if(data==null||be==null||!data.contains("id")||!data.getString("id").equals(be.saveWithFullMetadata(provider).getString("id")))return false;
            var actual=be.getBlockPos();data.putInt("x",actual.getX());data.putInt("y",actual.getY());data.putInt("z",actual.getZ());
            if(be.getLevel()==null||be.getLevel().isClientSide())return false;
            be.loadWithComponents(data,provider);
            if(!matches(level,pos,state,provider,payload))return false;
            // Precisely the Copycats+ 3.0.11 server redraw path: setChanged,
            // sendBlockUpdated at actual BE position and updateLight, no block setter.
            Class<?> redraw=Class.forName("com.copycatsplus.copycats.utility.BlockEntityUtils",false,be.getClass().getClassLoader());
            redraw.getMethod("redraw",BlockEntity.class).invoke(null,be);
            return matches(level,pos,state,provider,payload);
        }catch(ReflectiveOperationException|RuntimeException|LinkageError invalid){return false;}
    }
}
