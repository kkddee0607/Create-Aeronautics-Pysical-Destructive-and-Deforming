package com.physicaldestroydeform.diagnostics;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LogSelectionTest {
    @Test void silentDefaultAndUnknownTokens(){assertTrue(LogSelection.parse(null).isEmpty());assertTrue(LogSelection.parse("unknown").isEmpty());}
    @Test void selectiveChannelsDoNotEnableUnrelatedStages(){var s=LogSelection.parse(" contact,stress ");assertTrue(s.contains(LogSelection.Channel.CONTACT));assertTrue(s.contains(LogSelection.Channel.STRESS));assertFalse(s.contains(LogSelection.Channel.COMMIT));}
    @Test void allAndOffAreExplicit(){assertEquals(LogSelection.Channel.values().length,LogSelection.parse("all").size());assertTrue(LogSelection.parse("all,off").isEmpty());}
}
