package com.physicaldestroydeform.collision;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ContactBatchStampTest {
    private final Object server=new Object(),level=new Object();
    private static final String DIM="minecraft:overworld";
    private ContactBatchStamp stamp(long runtime,long game){return new ContactBatchStamp(server,level,DIM,runtime,game);}

    @Test void sameRuntimeTickGameAdvancesOne(){
        assertEquals(ContactBatchStamp.Status.CURRENT,stamp(720,77577).statusAgainst(stamp(720,77578)));
    }
    @Test void sameClockGameUnchanged(){
        assertEquals(ContactBatchStamp.Status.CURRENT,stamp(720,77577).statusAgainst(stamp(720,77577)));
    }
    @Test void lateBatchRejected(){
        assertEquals(ContactBatchStamp.Status.SERVER_TICK_MISMATCH,stamp(719,77578).statusAgainst(stamp(720,77578)));
    }
    @Test void otherServerRejected(){
        assertEquals(ContactBatchStamp.Status.SERVER_MISMATCH,stamp(720,77577).statusAgainst(new ContactBatchStamp(new Object(),level,DIM,720,77578)));
    }
    @Test void differentDimensionOrReloadedLevelRejected(){
        assertEquals(ContactBatchStamp.Status.DIMENSION_MISMATCH,stamp(720,77577).statusAgainst(new ContactBatchStamp(server,level,"minecraft:the_nether",720,77578)));
        assertEquals(ContactBatchStamp.Status.LEVEL_MISMATCH,stamp(720,77577).statusAgainst(new ContactBatchStamp(server,new Object(),DIM,720,77578)));
    }
    @Test void unknownStampRejected(){
        assertEquals(ContactBatchStamp.Status.UNKNOWN_STAMP,ContactBatchStamp.UNKNOWN.statusAgainst(stamp(720,77578)));
        assertEquals(ContactBatchStamp.Status.UNKNOWN_STAMP,stamp(720,77577).statusAgainst(ContactBatchStamp.UNKNOWN));
        assertFalse(ContactBatchStamp.UNKNOWN.sameCapture(ContactBatchStamp.UNKNOWN));
    }
    @Test void mismatchedPreEpochRejected(){
        var source=stamp(720,77577);
        assertEquals(ContactBatchStamp.Status.CURRENT,ContactBatchStamp.preStatus(source,List.of(pre(source,31),pre(source,32))));
        assertEquals(ContactBatchStamp.Status.PRE_MISSING,ContactBatchStamp.preStatus(source,List.of()));
        assertEquals(ContactBatchStamp.Status.PRE_DUPLICATE,ContactBatchStamp.preStatus(source,List.of(pre(source,31),pre(source,31))));
        assertEquals(ContactBatchStamp.Status.PRE_CONTRADICTORY,ContactBatchStamp.preStatus(source,List.of(pre(source,31),pre(stamp(719,77577),32))));
        assertEquals(ContactBatchStamp.Status.PRE_CONTRADICTORY,ContactBatchStamp.preStatus(source,List.of(pre(source,31),pre(stamp(720,77578),32))));
    }
    @Test void oldCacheAloneCannotAuthorize(){
        var previous=stamp(719,77577);var source=stamp(720,77577);
        assertFalse(previous.sameCapture(source));
        assertEquals(ContactBatchStamp.Status.NATIVE_MISSING,ContactBatchStamp.nativeStatus(source,List.of()));
        assertEquals(ContactBatchStamp.Status.NATIVE_CONTRADICTORY,ContactBatchStamp.nativeStatus(source,List.of(previous)));
    }
    @Test void nativeBatchRequiresIdenticalCaptureNotOnlyGameTime(){
        var source=stamp(720,77577);
        assertEquals(ContactBatchStamp.Status.CURRENT,ContactBatchStamp.nativeStatus(source,List.of(source,stamp(720,77577))));
        assertEquals(ContactBatchStamp.Status.NATIVE_CONTRADICTORY,ContactBatchStamp.nativeStatus(source,List.of(source,stamp(720,77578))));
        assertEquals(ContactBatchStamp.Status.NATIVE_CONTRADICTORY,ContactBatchStamp.nativeStatus(source,List.of(source,ContactBatchStamp.UNKNOWN)));
    }
    @Test void sameTickDirectPairExcluded(){
        assertFalse(RealFixRuntimeBridge.secondaryPairEligible(true,1));
        assertFalse(RealFixRuntimeBridge.secondaryPairEligible(false,2));
        assertFalse(RealFixRuntimeBridge.secondaryPairEligible(false,0));
        assertTrue(RealFixRuntimeBridge.secondaryPairEligible(false,1));
    }
    private static CollisionFrame.Snapshot pre(ContactBatchStamp stamp,long epoch){
        return new CollisionFrame.Snapshot(epoch,stamp.sourceGameTick(),stamp.dimension(),CollisionFrame.Phase.PRE_SOLVER,List.of(),true,stamp);
    }
}
