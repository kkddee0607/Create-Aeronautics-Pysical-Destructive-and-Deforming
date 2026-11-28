package com.physicaldestroydeform.diagnostics;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LogSelectionTest {
    @Test void silentDefaultAndUnknownTokens(){assertTrue(LogSelection.parse(null).isEmpty());assertTrue(LogSelection.parse("unknown").isEmpty());}
    @Test void selectiveChannelsDoNotEnableUnrelatedStages(){var s=LogSelection.parse(" contact,stress ");assertTrue(s.contains(LogSelection.Channel.CONTACT));assertTrue(s.contains(LogSelection.Channel.STRESS));assertFalse(s.contains(LogSelection.Channel.COMMIT));}
    @Test void allAndOffAreExplicit(){assertEquals(LogSelection.Channel.values().length,LogSelection.parse("all").size());assertTrue(LogSelection.parse("all,off").isEmpty());}
    @Test void reasonSeparatesCopycatStorageAndLineageFromDamageAndCommit(){
        assertEquals(LogSelection.Channel.COPYCAT,LogSelection.channelFor(RogTrace.Stage.R07,"COPYCAT_MATERIAL"));
        assertEquals(LogSelection.Channel.DAMAGE,LogSelection.channelFor(RogTrace.Stage.R07,"MATERIAL"));
        assertEquals(LogSelection.Channel.STORAGE,LogSelection.channelFor(RogTrace.Stage.R10,"RECOVERY_RECONCILIATION"));
        assertEquals(LogSelection.Channel.LINEAGE,LogSelection.channelFor(RogTrace.Stage.R09,"LINEAGE_MOVE"));
        assertEquals(LogSelection.Channel.COMMIT,LogSelection.channelFor(RogTrace.Stage.R10,"BREAK_READBACK"));
    }
    @Test void explicitSelectionOverridesLegacyFlags(){
        assertTrue(LogSelection.configured("off",key->true).isEmpty());
        assertEquals(java.util.EnumSet.of(LogSelection.Channel.STRESS),LogSelection.configured("stress",key->true));
        assertEquals(java.util.EnumSet.of(LogSelection.Channel.NATIVE,LogSelection.Channel.CONTACT,LogSelection.Channel.FORCE),LogSelection.configured("native,contact,force",key->true));
    }
    @Test void noExplicitChannelAndDebugAloneRemainSilent(){
        assertTrue(LogSelection.configured(null,key->false).isEmpty());
        assertTrue(LogSelection.configured(null,key->key.equals("pdd.diagnostics.mode")).isEmpty());
        assertEquals(java.util.EnumSet.of(LogSelection.Channel.STRESS),LogSelection.configured(null,key->key.equals("pdd.stress.trace")));
    }
}
