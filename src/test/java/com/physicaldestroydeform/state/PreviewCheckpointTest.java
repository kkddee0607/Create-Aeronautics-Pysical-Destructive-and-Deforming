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
            assertEquals("pdd-0.4.0colFix-preview-only.bin",file.getFileName().toString());
            var firstPolicy=PreviewCheckpoint.file(dir,"11111111-1111-1111-1111-111111111111");
            var secondPolicy=PreviewCheckpoint.file(dir,"22222222-2222-2222-2222-222222222222");
            assertNotEquals(firstPolicy,secondPolicy);
            assertNull(PreviewCheckpoint.load(firstPolicy));
            var old=dir.resolve("pdd-0.4.0Fix-preview-only.bin");
            Files.write(old,new byte[]{1,2,3});
            assertNull(PreviewCheckpoint.load(file)); // old coefficients are not replayed
            Files.deleteIfExists(old);
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

    @Test void policyBoundPreviewFilesDoNotReplayDifferentCalibration() throws Exception {
        var dir=Files.createTempDirectory("pdd-policy-check-");
        var policyA=PreviewCheckpoint.file(dir,"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        var policyB=PreviewCheckpoint.file(dir,"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        try {
            var state=new SparseDamageState(new SparseStateKey("minecraft:overworld","PLOT",
                    "00000000-0000-0000-0000-000000000001",1,3,70,4,
                    "minecraft:stone|Block{minecraft:stone}"),1,0,0,123,1,200);
            var bytes=SparseStateCodec.encode(List.of(state));
            PreviewCheckpoint.save(policyA,bytes);
            assertNull(PreviewCheckpoint.load(policyB));
            assertArrayEquals(bytes,PreviewCheckpoint.load(policyA));
        } finally {
            Files.deleteIfExists(policyA);Files.deleteIfExists(policyB);Files.deleteIfExists(dir);
        }
    }
}
