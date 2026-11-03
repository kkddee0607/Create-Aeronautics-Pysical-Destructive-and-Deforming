package com.physicaldestroydeform.material;

import com.physicaldestroydeform.MaterialProperties;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MaterialRegistrySnapshotTest {
    @Test void immutableGenerationAndBlockStateSpecificValues() {
        ResourceLocation id=ResourceLocation.tryParse("minecraft:stone");
        String fingerprint="minecraft:stone|Block{minecraft:stone}[powered=true]";
        var standard=new MaterialProperties(100,200,10,30,false);
        var powered=new MaterialProperties(120,180,10,30,false);
        var snapshot=new MaterialRegistry.Snapshot(2,Map.of(id,standard),Map.of(fingerprint,powered),"rev");
        var next=new MaterialRegistry.Snapshot(3,Map.of(id,powered),Map.of(),"rev");
        assertEquals(2,snapshot.generation());assertEquals(3,next.generation());
        assertEquals(powered,snapshot.resolveFingerprint(fingerprint));
        assertEquals(standard,snapshot.resolveFingerprint("minecraft:stone|Block{minecraft:stone}"));
        assertEquals(standard,snapshot.definitions().get(id));
        assertThrows(UnsupportedOperationException.class,()->snapshot.definitions().clear());
        assertNull(snapshot.resolveFingerprint("unknown:missing|Block{unknown:missing}"));
    }
}
