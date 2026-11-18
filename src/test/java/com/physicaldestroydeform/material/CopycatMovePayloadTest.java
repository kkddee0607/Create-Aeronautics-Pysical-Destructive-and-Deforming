package com.physicaldestroydeform.material;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CopycatMovePayloadTest {
    @Test void payloadRoundTripPreservesNestedMaterialAndItemCT(){
        CompoundTag data=new CompoundTag();data.putString("id","copycats:copycat");
        CompoundTag slot=new CompoundTag();slot.putString("material","minecraft:iron_block");slot.putBoolean("enableCT",true);
        CompoundTag item=new CompoundTag();item.putString("id","minecraft:iron_block");item.putInt("count",1);slot.put("consumedItem",item);
        CompoundTag slots=new CompoundTag();slots.put("bottom",slot);data.put("material_data",slots);
        data.putInt("x",12);data.putInt("y",34);data.putInt("z",56);
        var material=CopycatMovePayload.positionIndependent(data);
        assertFalse(material.contains("x"));assertTrue(data.contains("x"));
        assertEquals(material, CopycatMovePayload.decode(CopycatMovePayload.encode(material)));
        assertNull(CopycatMovePayload.decode("invalid payload"));
    }
}
