package com.physicaldestroydeform.world;

import com.physicaldestroydeform.state.SparseStateKey;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** Fixture was produced by the original PDD 0.5.0 schema-1 implementation,
 * not a hand-written reinterpretation of its old binary format. */
final class WorldPersistentSchemaUpgradeTest {
    @Test void schemaOneCheckpointIsMigratedAtomicallyAndNewU16DamageSurvivesRestart() throws Exception {
        Path source=Paths.get(WorldPersistentSchemaUpgradeTest.class
                .getResource("/pdd-schema1-fixture/checkpoint.manifest").toURI()).getParent();
        Path root=Files.createTempDirectory("pdd-050fix-schema-upgrade-");
        try(var paths=Files.walk(source)){
            for(var p:paths.toList()){
                Path dest=root.resolve(source.relativize(p).toString());
                if(Files.isDirectory(p))Files.createDirectories(dest);
                else {Files.createDirectories(dest.getParent());Files.copy(p,dest);}
            }
        }
        Path old=root.resolve("absent-old-0.4.1.wal");
        var key=new SparseStateKey("minecraft:overworld","WORLD","minecraft:overworld",
                1,4,64,5,"minecraft:stone");
        var migrated=new WorldPersistentJournal(root,old);
        assertTrue(migrated.migrated());
        assertEquals(3,migrated.stats().generation());
        assertEquals(200,migrated.get(key).damage());
        assertEquals(255,migrated.get(key).remainder());
        assertNotNull(migrated.receipt("schema1-preloaded"));
        var r=new WorldReceiptJournal.Receipt("schema2-updated",key,
                WorldReceiptJournal.Status.APPLIED,"T1024",101);
        migrated.transition(r,new WorldReceiptJournal.State(key,"0-fingerprint",1024,0,1,101));
        var afterWal=new WorldPersistentJournal(root,old);
        assertEquals(1024,afterWal.get(key).damage());
        assertNotNull(afterWal.receipt("schema2-updated"));
        afterWal.checkpointBatch(WorldPersistentJournal.SAVE_DIRTY_BATCH);
        var afterCheckpoint=new WorldPersistentJournal(root,old);
        assertEquals(1024,afterCheckpoint.get(key).damage());
        assertNotNull(afterCheckpoint.receipt("schema1-preloaded"));
        assertNotNull(afterCheckpoint.receipt("schema2-updated"));
    }
}
