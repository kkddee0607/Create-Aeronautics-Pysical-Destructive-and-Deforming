package com.physicaldestroydeform.stress;

import com.physicaldestroydeform.world.WorldCommitCore;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.physicaldestroydeform.stress.StressPropagation.*;
import static com.physicaldestroydeform.stress.StressReservationJournal.*;
import static com.physicaldestroydeform.stress.StressRuntimeProtocolTest.*;

class StressLargeReservationTest {
    @TempDir Path path;

    private Reservation large(String id,int count){
        var seeds=new ArrayList<Seed>();
        var origins=new ArrayList<WorldCommitCore.Intent>();
        for(int i=0;i<count;i++){
            seeds.add(new Seed(node(i),100,null));
            origins.add(new WorldCommitCore.Intent("original:"+i,key(i),"rev0",400,MATERIAL,1,90,true));
        }
        return new Reservation(id,1,100,Policy.defaults(),SETTINGS,seeds,origins);
    }

    @Test void observedLargeProjectionAndFormerBoundaryRoundTripWithoutNewCredit()throws Exception{
        for(int count:new int[]{8192,8193,9216,9744}){
            Path dir=path.resolve("roots-"+count);
            var r=large("large-"+count,count);
            var j=new StressReservationJournal(dir);
            assertTrue(j.reserve(r));
            var reopened=new StressReservationJournal(dir);
            assertEquals(r,reopened.get(r.creditId()).reservation());
            assertEquals(100.0*count,reopened.get(r.creditId()).reservation().reservedQ(),1e-8);
            assertFalse(reopened.reserve(r));
            assertEquals(r.creditId(),reopened.sourceOwner("original:"+(count-1)));
        }
    }

    @Test void twoFullSidesShareOneRootExclusionAndOneDerivedCap()throws Exception{
        var r=large("both",65536);
        var j=new StressReservationJournal(path);
        assertTrue(j.reserve(r));
        assertEquals(r,new StressReservationJournal(path).get(r.creditId()).reservation());
        var s=r.resumePlanning(100);
        assertEquals(6_553_600,s.ledger().reservedQ(),1e-8);
        assertThrows(IllegalArgumentException.class,()->large("too-many",65537));
    }

    @Test void rootsAcrossOldBoundaryNeverReceiveDerivedDamage(){
        var r=large("merge",9216);
        var s=r.resumePlanning(100);
        for(long tick=100;tick<200&&!s.ledger().finished();tick++)s.advance(line(9216),tick,512);
        assertTrue(s.ledger().finished());
        assertEquals(1,s.plans().size());
        assertEquals(node(9216).cell(),s.plans().getFirst().node().cell());
        assertEquals(50,s.plans().getFirst().incomingQ(),1e-9);
        assertEquals(30,s.ledger().directQ(),1e-9);
        assertEquals(7.5,s.ledger().plasticUnusedQ(),1e-9);
        assertEquals(921562.5,s.ledger().unusedQ(),1e-7);
        assertEquals(921600,s.ledger().accountedQ(),1e-7);
    }

    @Test void largeFrozenGraphAndClosedRetrySurviveRestart()throws Exception{
        var r=large("frozen",9744);
        var j=new StressReservationJournal(path);j.reserve(r);
        Map<Cell,Probe> graph=new TreeMap<>();
        for(int i=0;i<68000;i++)graph.put(node(i).cell(),Probe.empty());
        j.snapshot(r.creditId(),graph);
        var restarted=new StressReservationJournal(path);
        assertEquals(graph,restarted.get(r.creditId()).snapshot());
        restarted.cancelUnplanned(r.creditId(),"TTL");
        Path active;
        try(var files=Files.list(path)){active=files.filter(p->p.toString().endsWith(".psr")).findFirst().orElseThrow();}
        byte[] closedActive=Files.readAllBytes(active);
        restarted.compactHandoff(r.creditId());
        // Crash after the compact file was durable but before active deletion.
        Files.write(active,closedActive);
        var closed=new StressReservationJournal(path);
        assertTrue(closed.isArchived(r.creditId()));
        assertFalse(Files.exists(active));
        assertFalse(closed.reserve(r));
        assertEquals(974400,closed.archivedLedger(r.creditId()).unusedQ(),1e-8);
        assertEquals(r.creditId(),closed.sourceOwner("original:9743"));
    }

    @Test void compressedFramePreservesCanonicalLegacyHashAndRejectsTrailingBytes()throws Exception{
        var r=large("frame",9216);
        var entry=new Entry(r,Phase.RESERVED,List.of(),null,"RESERVED");
        byte[] canonical=encodeCanonical(entry),stored=encode(entry);
        assertTrue(stored.length<canonical.length);
        assertEquals(entry,decode(stored));
        byte[] trailing=Arrays.copyOf(stored,stored.length+1);
        assertThrows(java.io.IOException.class,()->decode(trailing));
        // Uncompressed v4 remains readable and retains the exact canonical bytes.
        var small=new Entry(reservation("v4",Policy.defaults()),Phase.RESERVED,List.of(),null,"RESERVED");
        assertArrayEquals(encodeCanonical(small),encode(small));
        assertEquals(small,decode(encodeCanonical(small)));
        byte[] corrupt=canonical.clone();corrupt[corrupt.length-1]^=1;
        assertThrows(java.io.IOException.class,()->decode(corrupt));
    }

    @Test void zeroQRootsStillExcludeDerivedTargetsWithoutReadingEmptySource(){
        var roots=List.of(new Seed(node(0),100,null),new Seed(node(1),0,null));
        var s=new Session(roots,Policy.defaults(),SETTINGS,100,true);
        int[] reads={0};
        s.advance(cell->{reads[0]++;return line(1).read(cell);},100,512);
        assertTrue(s.ledger().finished());assertTrue(s.plans().isEmpty());
        assertEquals(100,s.ledger().unusedQ(),1e-12);
        assertEquals(7,reads[0]); // only the non-zero source and its six faces
    }
}
