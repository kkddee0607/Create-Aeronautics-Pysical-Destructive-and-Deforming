package com.physicaldestroydeform.material;

import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies actual packaged classpath tables rather than test-only external CSVs. */
final class MaterialCsvIntegrationTest {
    @Test void bundledRowsAndPrecedenceAreStable() throws Exception {
        try(var block=MaterialCsvIntegrationTest.class.getResourceAsStream("/pdd/materials/blocks.csv");
            var item=MaterialCsvIntegrationTest.class.getResourceAsStream("/pdd/materials/items.csv")) {
            assertNotNull(block);assertNotNull(item);
            var data=MaterialCsvCatalog.parse(new InputStreamReader(block,StandardCharsets.UTF_8),
                    new InputStreamReader(item,StandardCharsets.UTF_8),s->true,s->true,1,"jar");
            assertEquals(1844,data.blocks().size());assertEquals(2159,data.items().size());
            assertEquals(59,data.blocks().get("minecraft:stone").h());
            // Match the authoritative T1024 source sheet, not the old 0.4.0 balancing override.
            assertEquals(19,data.blocks().get("minecraft:slime_block").t());
            assertEquals(100,data.blocks().get("minecraft:slime_block").e());
        }
    }
    @Test void t1024FourDigitExternalValuesAreAcceptedWithoutClamping() throws Exception {
        String src="id,H,T,D,E,unbreakable\n"+
                "minecraft:stone,,1024,,,\n"+
                "minecraft:oak_planks,,1025,,,\n";
        var parsed=MaterialCsvCatalog.parseUser(new StringReader(src),null,s->true,s->true);
        assertEquals(1,parsed.blocks().size());assertEquals(1,parsed.invalidRows());
        assertEquals(1024,parsed.blocks().get("minecraft:stone").t());
    }
    @Test void userPartialZeroAndDuplicateQuarantine() throws Exception {
        String head="id,H,T,D,E,unbreakable\n";
        var layer=MaterialCsvCatalog.parseUser(new StringReader(head+
                        "minecraft:stone,0,,12,,\n"+
                        "minecraft:slime_block,,75,,94,\n"+
                        "minecraft:oak_planks,22,88,,11,\n"+
                        "minecraft:oak_planks,99,99,99,99,\n"),null,s->true,s->true);
        assertEquals(2,layer.blocks().size());
        assertEquals(1,layer.duplicateIds());
        var original=new com.physicaldestroydeform.MaterialProperties(59,91,10,9,false);
        var merged=layer.blocks().get("minecraft:stone").resolve(original);
        assertEquals(0,merged.hardness());assertEquals(91,merged.threshold());
        assertEquals(12,merged.ductilityPercent());assertEquals(9,merged.elasticityPercent());
    }
    @Test void legacyCsvSelfTestStillPasses() throws Exception {
        MaterialCsvCatalogSelfTest.main(new String[0]);
    }
}
