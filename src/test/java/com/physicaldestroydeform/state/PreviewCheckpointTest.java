package com.physicaldestroydeform.state;

import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreviewCheckpointTest {
    @Test void diskRoundtripIsSeparatedFromAnyMinecraftState() throws Exception {
        var dir=Files.createTempDirectory("pdd-preview-check-");
        try {
            var file=PreviewCheckpoint.file(dir);
            assertNull(PreviewCheckpoint.load(file));
            var state=new SparseDamageState(new SparseStateKey("minecraft:overworld","PLOT",
                    "00000000-0000-0000-0000-000000000001",1,3,70,4,
                    "minecraft:stone|Block{minecraft:stone}"),1,0,0,123,1,200);
            var bytes=SparseStateCodec.encode(List.of(state));
            PreviewCheckpoint.save(file,bytes);
            assertArrayEquals(bytes,PreviewCheckpoint.load(file));
            var corrupt=bytes.clone();corrupt[15]^=1;
            assertThrows(IllegalArgumentException.class,()->PreviewCheckpoint.save(file,corrupt));
            assertArrayEquals(bytes,PreviewCheckpoint.load(file));
        } finally {
            Files.deleteIfExists(PreviewCheckpoint.file(dir));Files.deleteIfExists(dir);
        }
    }
}
