package com.physicaldestroydeform.state;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;

/** Schema v2 U16 damage, backwards v1 reader; deterministic CRC32. Pure Java. */
public final class SparseStateCodec {
    private static final int MAGIC=0x50444434; // PDD4
    public static final int SCHEMA_VERSION=2;
    public static final int MAX_RECORDS=65536;
    public static final int MAX_BYTES=16*1024*1024;
    private SparseStateCodec() {}
    public static byte[] encode(List<SparseDamageState> records) {
        if(records==null||records.size()>MAX_RECORDS)throw new IllegalArgumentException("invalid record count");
        try {
            List<SparseDamageState> sorted=new ArrayList<>(records);
            sorted.sort((a,b)->a.key().compareTo(b.key()));
            ByteArrayOutputStream payload=new ByteArrayOutputStream();
            DataOutputStream out=new DataOutputStream(payload);
            out.writeInt(MAGIC);out.writeInt(SCHEMA_VERSION);out.writeInt(sorted.size());
            SparseStateKey previous=null;
            for(SparseDamageState state:sorted) {
                if(state==null || state.key().equals(previous))throw new IllegalArgumentException("duplicate/null sparse key");
                previous=state.key();
                SparseStateKey k=state.key();
                out.writeUTF(k.dimension());out.writeUTF(k.ownerSpace());out.writeUTF(k.ownerId());
                out.writeLong(k.ownerGeneration());
                out.writeInt(k.x());out.writeInt(k.y());out.writeInt(k.z());out.writeUTF(k.fingerprint());
                out.writeShort(state.damageU16());out.writeByte(state.deformationU8());
                out.writeByte(state.crackVisualU8());out.writeByte(state.remainderU8());
                out.writeLong(state.materialGeneration());out.writeLong(state.lastUpdateTick());
                if(payload.size()>MAX_BYTES-4)throw new IllegalArgumentException("sparse payload too large");
            }
            out.flush();byte[] data=payload.toByteArray();
            CRC32 crc=new CRC32();crc.update(data);
            out.writeInt((int)crc.getValue());out.flush();
            return payload.toByteArray();
        } catch(IOException error){throw new IllegalArgumentException("invalid sparse encoding",error);}
    }
    public static List<SparseDamageState> decode(byte[] encoded) {
        if(encoded==null||encoded.length<16||encoded.length>MAX_BYTES)
            throw new IllegalArgumentException("invalid payload length");
        int size=encoded.length;
        CRC32 crc=new CRC32();crc.update(encoded,0,size-4);
        int stored=((encoded[size-4]&255)<<24)|((encoded[size-3]&255)<<16)|
                ((encoded[size-2]&255)<<8)|(encoded[size-1]&255);
        if(stored!=(int)crc.getValue())throw new IllegalArgumentException("sparse checksum mismatch");
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(encoded,0,size-4))) {
            if(in.readInt()!=MAGIC)throw new IllegalArgumentException("unknown sparse magic");
            int schema=in.readInt();
            if(schema!=1&&schema!=SCHEMA_VERSION)throw new IllegalArgumentException("unsupported sparse schema");
            int count=in.readInt();
            if(count<0||count>MAX_RECORDS)throw new IllegalArgumentException("invalid record count");
            List<SparseDamageState> result=new ArrayList<>(count);
            Set<SparseStateKey> seen=new HashSet<>();
            for(int i=0;i<count;i++) {
                SparseStateKey key=new SparseStateKey(in.readUTF(),in.readUTF(),in.readUTF(),in.readLong(),
                        in.readInt(),in.readInt(),in.readInt(),in.readUTF());
                int damage=schema==1?in.readUnsignedByte():in.readUnsignedShort();
                int deformation=in.readUnsignedByte(),
                        crack=in.readUnsignedByte(),remainder=in.readUnsignedByte();
                SparseDamageState state=new SparseDamageState(key,damage,deformation,crack,remainder,
                        in.readLong(),in.readLong());
                if(!seen.add(key))throw new IllegalArgumentException("duplicate sparse key");
                result.add(state);
            }
            if(in.available()!=0)throw new IllegalArgumentException("unexpected trailing sparse bytes");
            return List.copyOf(result);
        }catch(IOException error){throw new IllegalArgumentException("truncated/invalid sparse payload",error);}
    }
}
