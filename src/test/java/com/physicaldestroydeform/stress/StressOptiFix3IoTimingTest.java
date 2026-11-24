package com.physicaldestroydeform.stress;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.physicaldestroydeform.stress.StressRuntimeProtocolTest.reservation;
import com.physicaldestroydeform.stress.StressPropagation.Policy;

final class StressOptiFix3IoTimingTest {
    @TempDir Path temp;
    @Test void reservationRecordsSeparateDurableStagesWithoutChangingSavedIdentity()throws Exception{
        var r=reservation("io-stage",Policy.defaults());
        var journal=new StressReservationJournal(temp);
        assertTrue(journal.reserve(r));
        String metrics=journal.ioPerformance();
        for(String phase:new String[]{"ENCODE=","FILE_WRITE=","FILE_FORCE=","ATOMIC_MOVE=","DIRECTORY_FORCE="})
            assertTrue(metrics.contains(phase),phase+" missing: "+metrics);
        assertEquals(r,new StressReservationJournal(temp).get("io-stage").reservation());
    }
    @Test void duplicateReservationDoesNotRewriteAlreadyPersistedSnapshot()throws Exception{
        var r=reservation("io-duplicate",Policy.defaults());
        var journal=new StressReservationJournal(temp);
        assertTrue(journal.reserve(r));
        var metrics=journal.ioPerformance();
        assertFalse(journal.reserve(r));
        assertEquals(metrics,journal.ioPerformance(),"duplicate reservation must not issue another fsync");
    }
}
