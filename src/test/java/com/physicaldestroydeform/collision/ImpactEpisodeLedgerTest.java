package com.physicaldestroydeform.collision;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.physicaldestroydeform.collision.ImpactEpisodeLedger.*;

class ImpactEpisodeLedgerTest {
    @Test void reciprocalOrderAndRepeatedContactShareOneEpisode() {
        var ledger=new ImpactEpisodeLedger();
        var a=Key.of("overworld",42,"WORLD","PLOT:z");
        var b=Key.of("overworld",42,"PLOT:z","WORLD");
        assertEquals(a,b);
        var first=ledger.observe(a,"stone","steel");
        var second=ledger.observe(b,"steel","stone");
        ledger.observe(a,"stone2","steel");
        assertSame(first,second);
        assertEquals(1,ledger.size());assertEquals(3,first.observations());
        assertEquals(3,first.uniqueTargets());
    }
    @Test void stepAndDimensionIsolateEpisodes() {
        var ledger=new ImpactEpisodeLedger();
        ledger.observe(Key.of("overworld",1,"WORLD","PLOT:a"),"a","b");
        ledger.observe(Key.of("overworld",2,"WORLD","PLOT:a"),"a","b");
        ledger.observe(Key.of("nether",1,"WORLD","PLOT:a"),"a","b");
        assertEquals(3,ledger.size());
    }
    @Test void oneApprovedBudgetDespiteDuplicateCallbackAndRecoveryFromRejection() {
        var ledger=new ImpactEpisodeLedger();
        var episode=ledger.observe(Key.of("overworld",3,"WORLD","PLOT:a"),"stone","hull");
        assertFalse(episode.approveOnce(ImpactMath.Result.reject(ImpactMath.Reason.CONTACT_UNVERIFIED)).approved());
        var approved=new ImpactMath.Result(ImpactMath.Kind.EXACT_CONTACT,ImpactMath.Reason.APPROVED,
                12,3,2,"TEST_VALIDATED");
        assertSame(approved,episode.approveOnce(approved));
        assertEquals(ImpactMath.Reason.DUPLICATE_EPISODE,episode.approveOnce(approved).reason());
        assertEquals(12,episode.decision().budget());
    }
    @Test void rejectsUnknownAndIdenticalOwner() {
        assertThrows(IllegalArgumentException.class,()->Key.of("world",1,"PLOT:a","PLOT:a"));
        assertThrows(IllegalArgumentException.class,()->Key.of("world",1,null,"WORLD"));
        assertThrows(IllegalArgumentException.class,()->Key.of("world",-1,"PLOT:a","WORLD"));
    }
}
