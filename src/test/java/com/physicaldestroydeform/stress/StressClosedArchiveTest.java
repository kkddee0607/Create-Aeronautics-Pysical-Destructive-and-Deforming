package com.physicaldestroydeform.stress;

import com.physicaldestroydeform.world.WorldCommitCore;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.physicaldestroydeform.stress.StressReservationJournal.*;
import static com.physicaldestroydeform.stress.StressRuntimeProtocolTest.*;
import static com.physicaldestroydeform.stress.StressPropagation.*;

class StressClosedArchiveTest {
    @TempDir Path path;

    private Reservation withOrigin(String id,String originId,long tick){
        var origin=new WorldCommitCore.Intent(originId,key(0),"rev0",400,MATERIAL,1,90,true);
        return new Reservation(id,1,tick,Policy.defaults(),SETTINGS,List.of(new Seed(node(0),100,null)),List.of(origin));
    }
    @Test void completedLedgerAndOriginIdentitySurviveCompactionAndRestart()throws Exception {
        var journal=new StressReservationJournal(path);
        var r=withOrigin("closed","origin",100);journal.reserve(r);
        journal.cancelUnplanned(r.creditId(),"TTL");journal.compactHandoff(r.creditId());
        assertEquals(0,journal.activeCount());assertEquals(1,journal.archivedCount());assertEquals(0,journal.activeBytes());
        var restored=new StressReservationJournal(path);
        assertTrue(restored.isArchived(r.creditId()));assertEquals(100,restored.archivedLedger(r.creditId()).unusedQ(),1e-12);
        assertFalse(restored.reserve(r));assertTrue(restored.matchesArchivedRetry(withOrigin("closed","origin",101)));
        assertThrows(IllegalArgumentException.class,()->restored.reserve(withOrigin("other","origin",101)));
        assertThrows(IllegalArgumentException.class,()->restored.reserve(withOrigin("closed","different-origin",100)));
        assertTrue(restored.reserve(withOrigin("new","new-origin",101)));
    }

    @Test void interruptedArchiveWithOldCopyDoesNotReplay()throws Exception {
        var journal=new StressReservationJournal(path);var r=reservation("old",Policy.defaults());journal.reserve(r);
        journal.cancelUnplanned(r.creditId(),"TTL");
        Path old;try(var files=Files.list(path)){old=files.filter(p->p.toString().endsWith(".psr")).findFirst().orElseThrow();}
        byte[] original=Files.readAllBytes(old);
        journal.compactHandoff(r.creditId());Files.write(old,original);
        var restored=new StressReservationJournal(path);
        assertFalse(Files.exists(old));assertEquals(0,restored.activeCount());assertTrue(restored.isArchived(r.creditId()));
    }

    @Test void unfinishedReservationCannotBeArchived()throws Exception {
        var journal=new StressReservationJournal(path);var r=reservation("pending",Policy.defaults());journal.reserve(r);
        assertThrows(IllegalStateException.class,()->journal.compactHandoff(r.creditId()));
        assertEquals(1,journal.activeCount());assertFalse(journal.isArchived(r.creditId()));
    }

    @Test void oversizedAdmissionDoesNotFaultJournalOrPreventSmallReservation()throws Exception {
        var journal=new StressReservationJournal(path);
        var seeds=new ArrayList<Seed>();String fingerprint="x".repeat(60000);
        for(int i=0;i<72;i++)seeds.add(new Seed(new Node(node(i).cell(),fingerprint,"rev0",MATERIAL),1,null));
        var huge=new Reservation("huge",1,100,Policy.defaults(),SETTINGS,seeds);
        assertThrows(CapacityException.class,()->journal.reserve(huge));
        assertFalse(journal.faulted());assertEquals(0,journal.activeCount());
        assertTrue(journal.reserve(reservation("small",Policy.defaults())));
    }

    @Test void corruptedArchiveNeverDeletesOriginalOrAdmitsDuplicate()throws Exception {
        var journal=new StressReservationJournal(path);var r=reservation("old",Policy.defaults());journal.reserve(r);
        journal.cancelUnplanned(r.creditId(),"TTL");
        Path original;try(var files=Files.list(path)){original=files.filter(p->p.toString().endsWith(".psr")).findFirst().orElseThrow();}
        byte[] old=Files.readAllBytes(original);journal.compactHandoff(r.creditId());Files.write(original,old);
        Path compact;try(var files=Files.walk(path)){compact=files.filter(p->p.toString().endsWith(".psc")).findFirst().orElseThrow();}
        byte[] damaged=Files.readAllBytes(compact);damaged[12]^=1;Files.write(compact,damaged);
        assertThrows(java.io.IOException.class,()->new StressReservationJournal(path));assertArrayEquals(old,Files.readAllBytes(original));
    }

    @Test void finishedSealedOutputCanBeArchivedWithoutRewritingFullGraph()throws Exception {
        var journal=new StressReservationJournal(path);var r=reservation("sealed",Policy.defaults());journal.reserve(r);
        var session=new Session(r.seeds(),r.policy(),r.settings(),100);session.advance(line(1),100,512);
        journal.seal(r.creditId(),session);
        Path old;try(var files=Files.list(path)){old=files.filter(p->p.toString().endsWith(".psr")).findFirst().orElseThrow();}
        byte[] original=Files.readAllBytes(old);journal.compactHandoff(r.creditId());Files.write(old,original);
        var reopened=new StressReservationJournal(path);assertFalse(Files.exists(old));assertEquals(0,reopened.activeCount());
        var ledger=reopened.archivedLedger(r.creditId());
        assertEquals(100,ledger.accountedQ(),1e-12);assertEquals(0,ledger.pendingQ(),1e-12);assertEquals(60,ledger.directQ(),1e-12);
    }
}
