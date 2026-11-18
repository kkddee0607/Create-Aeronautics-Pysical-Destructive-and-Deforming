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
    @Test void schemaThreeStateAndPendingPayloadSurviveHistoryMigration() throws Exception {
        Path source=Paths.get(getClass().getResource("/pdd-schema3-fixture/checkpoint.manifest").toURI()).getParent();
        Path root=Files.createTempDirectory("pdd-schema3-upgrade-");
        try(var paths=Files.walk(source)) {
            for(var p:paths.toList()) {
                Path target=root.resolve(source.relativize(p).toString());
                if(Files.isDirectory(p))Files.createDirectories(target);
                else {Files.createDirectories(target.getParent());Files.copy(p,target);}
            }
        }
        var j=new WorldPersistentJournal(root,root.resolve("absent.wal"));
        var key=new SparseStateKey("minecraft:overworld","WORLD","minecraft:overworld",1,4,64,5,"minecraft:stone");
        assertTrue(j.migrated());assertEquals(777,j.get(key).damage());
        assertEquals(123,j.get(key).remainder());assertEquals(0,j.get(key).deformation());
        var p=j.receipt("schema3-pending").pending();assertNotNull(p);
        assertEquals(1.25,p.intent().sealedQ());assertEquals(1024,p.intent().material().threshold());
        assertEquals(20,p.intent().material().ductilityPercent());assertEquals(30,p.intent().material().elasticityPercent());
        assertEquals(7,p.intent().materialGeneration());assertTrue(p.intent().fFirstPreDamped());
        assertEquals(123,p.intent().eventTick());assertEquals(9,p.order());assertEquals(2345,p.expiresGameTick());
        assertEquals(com.physicaldestroydeform.DamageModel.Settings.defaults(),p.settings());
        var reopened=new WorldPersistentJournal(root,root.resolve("absent.wal"));
        assertEquals(j.get(key),reopened.get(key));assertEquals(p,reopened.receipt("schema3-pending").pending());
    }
    @Test void schemaTwoDamageRevisionAndLegacyReceiptsSurviveSchemaThreeMigration() throws Exception {
        Path source=Paths.get(getClass().getResource("/pdd-schema2-fixture/checkpoint.manifest").toURI()).getParent();
        Path root=Files.createTempDirectory("pdd-persistfix-schema2-");
        try(var paths=Files.walk(source)){
            for(var p:paths.toList()){
                Path dest=root.resolve(source.relativize(p).toString());
                if(Files.isDirectory(p))Files.createDirectories(dest);
                else {Files.createDirectories(dest.getParent());Files.copy(p,dest);}
            }
        }
        var key=new SparseStateKey("minecraft:overworld","WORLD","minecraft:overworld",1,4,64,5,"minecraft:stone");
        var old=root.resolve("absent.wal");
        var j=new WorldPersistentJournal(root,old);
        assertTrue(j.migrated());assertEquals(3,j.stats().generation());
        assertEquals(777,j.get(key).damage());assertEquals(123,j.get(key).remainder());
        assertEquals(1,j.revision(key.dimension(),key.x(),key.y(),key.z()));
        assertEquals(7,j.get(key).materialGeneration());
        assertNull(j.receipt("schema2-deferred").pending());
        assertEquals(WorldReceiptJournal.Status.PREPARED,j.receipt("schema2-prepared").status());
        var reopened=new WorldPersistentJournal(root,old);
        assertEquals(j.get(key),reopened.get(key));
        assertEquals(j.receipt("schema2-deferred"),reopened.receipt("schema2-deferred"));
        assertEquals(j.receipt("schema2-prepared"),reopened.receipt("schema2-prepared"));
    }
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
