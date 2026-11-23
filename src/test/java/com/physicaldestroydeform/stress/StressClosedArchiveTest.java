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

    @Test void noCacheStillFindsArchivedOriginAndLedgerExactly()throws Exception {
        var journal=new StressReservationJournal(path,0);
        var r=withOrigin("closed","origin",100);journal.reserve(r);
        journal.cancelUnplanned(r.creditId(),"TTL");journal.compactHandoff(r.creditId());
        assertEquals(0,journal.activeSourceCount());assertEquals(0,journal.cachedArchiveBytes());
        assertEquals("closed",journal.sourceOwner("origin"));assertNull(journal.sourceOwner("origin-2"));
        assertTrue(journal.isArchived("closed"));assertEquals(100,journal.archivedLedger("closed").unusedQ(),1e-12);
        assertThrows(IllegalArgumentException.class,()->journal.reserve(withOrigin("other","origin",101)));
        var reopened=new StressReservationJournal(path,0);
        assertEquals("closed",reopened.sourceOwner("origin"));assertFalse(reopened.reserve(r));
    }

    @Test void archiveCacheEvictionDoesNotLoseDedupOrOwnership()throws Exception {
        var journal=new StressReservationJournal(path,4096);
        for(int i=0;i<12;i++){
            var r=withOrigin("c-"+i,"o-"+i,100);journal.reserve(r);
            journal.cancelUnplanned(r.creditId(),"TTL");journal.compactHandoff(r.creditId());
        }
        assertEquals(12,journal.archivedCount());assertEquals(0,journal.activeSourceCount());
        for(int i=0;i<12;i++){
            assertEquals("c-"+i,journal.sourceOwner("o-"+i));
            assertEquals(100,journal.archivedLedger("c-"+i).unusedQ(),1e-12);
            assertTrue(journal.cachedArchiveBytes()<=4096);
        }
        assertThrows(IllegalArgumentException.class,()->journal.reserve(withOrigin("wrong","o-0",101)));
    }

    @Test void activeSourcesRemainReservedDuringArchiveEviction()throws Exception {
        var journal=new StressReservationJournal(path,0);
        var active=withOrigin("active","a",100);journal.reserve(active);
        var closed=withOrigin("closed","b",100);journal.reserve(closed);
        journal.cancelUnplanned(closed.creditId(),"TTL");journal.compactHandoff(closed.creditId());
        assertEquals(1,journal.activeSourceCount());assertEquals("active",journal.sourceOwner("a"));
        assertEquals("closed",journal.sourceOwner("b"));
        var reopened=new StressReservationJournal(path,0);
        assertEquals(1,reopened.activeSourceCount());assertEquals("active",reopened.sourceOwner("a"));
    }

    @Test void indexIsRebuiltFromAuthoritativeArchiveAfterCorruptSidecar()throws Exception {
        var journal=new StressReservationJournal(path,0);
        var r=withOrigin("closed","origin",100);journal.reserve(r);
        journal.cancelUnplanned(r.creditId(),"TTL");journal.compactHandoff(r.creditId());
        Path index;try(var files=Files.list(path.resolve("origin-index-v1"))){index=files.findFirst().orElseThrow();}
        Files.write(index,new byte[]{1,2,3});
        var reopened=new StressReservationJournal(path,0);
        assertEquals("closed",reopened.sourceOwner("origin"));assertFalse(reopened.reserve(r));
    }

    @Test void corruptLiveIndexFailsClosedInsteadOfReturningMissingOwner()throws Exception {
        var journal=new StressReservationJournal(path,0);
        var r=withOrigin("closed","origin",100);journal.reserve(r);
        journal.cancelUnplanned(r.creditId(),"TTL");journal.compactHandoff(r.creditId());
        Path index;try(var files=Files.list(path.resolve("origin-index-v1"))){index=files.findFirst().orElseThrow();}
        Files.delete(index);
        assertThrows(java.io.IOException.class,()->journal.sourceOwner("origin"));
        assertTrue(journal.faulted());assertThrows(java.io.IOException.class,()->journal.reserve(withOrigin("new","origin",101)));
    }

    @Test void startupRejectsConflictingArchivedOriginsAcrossCreditFiles()throws Exception {
        Path first=path.resolve("first"),second=path.resolve("second");
        for(Path dir:List.of(first,second)){
            var journal=new StressReservationJournal(dir,0);var r=withOrigin(dir.getFileName().toString(),"same",100);
            journal.reserve(r);journal.cancelUnplanned(r.creditId(),"TTL");journal.compactHandoff(r.creditId());
        }
        Path root=second.resolve("closed-v1");Path foreign;
        try(var files=Files.walk(root)){foreign=files.filter(p->p.toString().endsWith(".psc")).findFirst().orElseThrow();}
        Path target=first.resolve("closed-v1").resolve(root.relativize(foreign));Files.createDirectories(target.getParent());Files.copy(foreign,target);
        assertThrows(java.io.IOException.class,()->new StressReservationJournal(first,0));
    }

    @Test void sameHashSegmentColdLookupSurvivesMultipleAppendsAndMissingKey()throws Exception {
        // Exercise candidate table growth, cold negatives and append-only
        // history even when all origin keys fall in one segment.
        List<String> origins=new ArrayList<>();
        for(int n=0;origins.size()<12;n++){
            String origin="bucket-test-"+n;
            if(hash(("o:"+origin).getBytes(java.nio.charset.StandardCharsets.UTF_8)).startsWith("63"))origins.add(origin);
        }
        var journal=new StressReservationJournal(path,0);
        Path index=path.resolve("origin-index-v1/owners.psi");byte[] prefix=null;
        for(int n=10;n>=0;n--){
            var r=withOrigin("credit-"+n,origins.get(n),100);journal.reserve(r);
            journal.cancelUnplanned(r.creditId(),"TTL");journal.compactHandoff(r.creditId());
            if(prefix==null)prefix=Files.readAllBytes(index);
        }
        assertArrayEquals(prefix,Arrays.copyOf(Files.readAllBytes(index),prefix.length));
        assertNull(journal.sourceOwner(origins.get(11)));
        assertTrue(journal.cacheStatus().contains("originDiskLookups=0"));
        for(int n=0;n<11;n++)assertEquals("credit-"+n,journal.sourceOwner(origins.get(n)));
        assertNull(journal.sourceOwner(origins.get(11)));
        var reopened=new StressReservationJournal(path,0);
        for(int n=0;n<11;n++)assertEquals("credit-"+n,reopened.sourceOwner(origins.get(n)));
        assertNull(reopened.sourceOwner(origins.get(11)));
    }

    @Test void startupRejectsAnActiveOriginOwnedByAnotherClosedCredit()throws Exception {
        Path first=path.resolve("first"),second=path.resolve("second");
        var archived=new StressReservationJournal(first,0);var r=withOrigin("closed","shared",100);archived.reserve(r);
        archived.cancelUnplanned(r.creditId(),"TTL");archived.compactHandoff(r.creditId());
        var active=new StressReservationJournal(second,0);active.reserve(withOrigin("active","shared",100));
        Path source;try(var files=Files.list(second)){source=files.filter(p->p.toString().endsWith(".psr")).findFirst().orElseThrow();}
        Path copy=first.resolve(source.getFileName());Files.copy(source,copy);
        assertThrows(java.io.IOException.class,()->new StressReservationJournal(first,0));
        assertTrue(Files.exists(copy));
    }

    @Test void knownArchivedCreditWithMissingColdReceiptFailsClosedEvenWithoutOrigins()throws Exception {
        var journal=new StressReservationJournal(path,0);var r=reservation("closed",Policy.defaults());journal.reserve(r);
        journal.cancelUnplanned(r.creditId(),"TTL");journal.compactHandoff(r.creditId());
        Path receipt;try(var files=Files.walk(path.resolve("closed-v1"))){receipt=files.filter(p->p.toString().endsWith(".psc")).findFirst().orElseThrow();}
        Files.delete(receipt);
        assertThrows(java.io.IOException.class,()->journal.reserve(r));
        assertTrue(journal.faulted());
    }
}
