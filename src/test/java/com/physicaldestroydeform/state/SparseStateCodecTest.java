package com.physicaldestroydeform.state;

import com.physicaldestroydeform.BlockDamageState;
import com.physicaldestroydeform.collision.GameImpactCredit;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import static org.junit.jupiter.api.Assertions.*;

class SparseStateCodecTest {
    private static final String UUID="5069c055-4ebd-415c-b205-a6715b83156d";
    private static SparseStateKey plot(int x) {
        return new SparseStateKey("minecraft:overworld","PLOT",UUID,4,x,-2,11,
                "minecraft:stone|Block{minecraft:stone}");
    }
    private static SparseDamageState sample(int x) {
        return new SparseDamageState(plot(x),7,1,8,91,42,1000);
    }
    @Test void deterministicRoundtripAndUnsignedFields() {
        var a=sample(2);var b=sample(-3);
        byte[] first=SparseStateCodec.encode(List.of(a,b));
        assertArrayEquals(first,SparseStateCodec.encode(List.of(b,a)));
        assertEquals(List.of(b,a),SparseStateCodec.decode(first));
    }
    @Test void highUnsignedByteAndRemainderRoundTrip() {
        var key=plot(10);
        var state=new SparseDamageState(key,255,255,255,255,1,10);
        var decoded=SparseStateCodec.decode(SparseStateCodec.encode(List.of(state))).getFirst();
        assertEquals(255,decoded.unpack().fixedDamage()%256);
        assertEquals(255,decoded.unpack().damage());
        assertEquals(255,decoded.unpack().deformation());
    }
    @Test void zeroStateCannotBeSerialized() {
        assertThrows(IllegalArgumentException.class,()->new SparseDamageState(plot(1),0,0,0,0,1,1));
        var zero=new BlockDamageState();
        assertThrows(IllegalArgumentException.class,()->SparseDamageState.from(plot(1),zero,1,1));
    }
    @Test void invalidOwnerGenerationOrWrongSpaceRejected() {
        assertThrows(IllegalArgumentException.class,()->new SparseStateKey("minecraft:overworld","PLOT",UUID,0,0,0,0,"x"));
        assertThrows(IllegalArgumentException.class,()->new SparseStateKey("minecraft:overworld","WORLD","minecraft:the_nether",1,0,0,0,"x"));
        assertThrows(IllegalArgumentException.class,()->new SparseStateKey("minecraft:overworld","PLOT","invalid-id",1,0,0,0,"x"));
    }
    @Test void keyFromRealVerifiedTargetRejectsOtherOwner() {
        var owner=new GameImpactCredit.Owner("PLOT:"+UUID,9);
        var target=new GameImpactCredit.Target(owner.stableKey(),"-2,77,11","minecraft:glass|Block{minecraft:glass}");
        var key=SparseStateKey.fromTarget("minecraft:overworld",owner,target);
        assertEquals(-2,key.x());assertEquals(77,key.y());assertEquals(9,key.ownerGeneration());
        assertThrows(IllegalArgumentException.class,()->SparseStateKey.fromTarget(
                "minecraft:overworld",owner,new GameImpactCredit.Target("WORLD:minecraft:overworld","1,2,3","x")));
    }
    @Test void badCrcAndTruncatedBlobFailClosed() {
        var payload=SparseStateCodec.encode(List.of(sample(1)));
        payload[15]^=7;
        assertThrows(IllegalArgumentException.class,()->SparseStateCodec.decode(payload));
        var valid=SparseStateCodec.encode(List.of(sample(1)));
        assertThrows(IllegalArgumentException.class,()->SparseStateCodec.decode(Arrays.copyOf(valid,valid.length-2)));
    }
    @Test void schemaMutationWithValidCrcStillRejected() {
        var payload=SparseStateCodec.encode(List.of(sample(3)));
        payload[7]=2;
        CRC32 crc=new CRC32();crc.update(payload,0,payload.length-4);
        int v=(int)crc.getValue();
        int n=payload.length;
        payload[n-4]=(byte)(v>>>24);payload[n-3]=(byte)(v>>>16);
        payload[n-2]=(byte)(v>>>8);payload[n-1]=(byte)v;
        assertThrows(IllegalArgumentException.class,()->SparseStateCodec.decode(payload));
    }
    @Test void duplicateKeysRejected() {
        assertThrows(IllegalArgumentException.class,()->SparseStateCodec.encode(List.of(sample(2),sample(2))));
    }
    @Test void emptyRecordSequenceHasValidHeaderAndCrc() {
        assertEquals(List.of(),SparseStateCodec.decode(SparseStateCodec.encode(List.of())));
    }
}
