package com.physicaldestroydeform.plot;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlotHeatMapReadCacheTest {
    private static final PlotHeatMapReadCache.Pending BUSY = new PlotHeatMapReadCache.Pending(true,false,7,2,0,0);

    @Test void repeatsOnlyProvenDenialWithinTheSameTick() {
        var cache=new PlotHeatMapReadCache();var reads=new AtomicInteger();
        assertEquals(BUSY,cache.pending(50,false,()->{reads.incrementAndGet();return BUSY;}));
        for(int i=0;i<20;i++)assertEquals(BUSY,cache.pending(50,false,()->{reads.incrementAndGet();return BUSY;}));
        assertEquals(1,reads.get());
        assertNull(cache.pending(51,false,()->{reads.incrementAndGet();return null;}));
        // A clear observation is never a cached grant of permission.
        assertEquals(BUSY,cache.pending(51,false,()->{reads.incrementAndGet();return BUSY;}));
        assertEquals(3,reads.get());
    }

    @Test void nativeMutationInvalidatesEvenWhenTickIsUnchanged() {
        var cache=new PlotHeatMapReadCache();
        assertEquals(BUSY,cache.pending(50,false,()->BUSY));
        cache.beginMutation();cache.endMutation();
        assertNull(cache.pending(50,false,()->null));
        assertEquals(BUSY,cache.pending(50,false,()->BUSY));
    }

    @Test void nestedNativeMutationsCannotPublishAnIntermediateCache() {
        var cache=new PlotHeatMapReadCache();var changed=new PlotHeatMapReadCache.Pending(true,true,0,1,0,0);
        cache.beginMutation();cache.beginMutation();
        assertEquals(BUSY,cache.pending(50,false,()->BUSY));
        cache.endMutation();
        assertEquals(changed,cache.pending(50,false,()->changed));
        cache.endMutation();
        assertNull(cache.pending(50,false,()->null));
    }

    @Test void mutationBoundaryAndReadbackAlwaysReadLiveAfterCachedDenial() {
        var cache=new PlotHeatMapReadCache();var reads=new AtomicInteger();
        assertEquals(BUSY,cache.pending(50,false,()->BUSY));
        assertNull(cache.pending(50,true,()->{reads.incrementAndGet();return null;}));
        assertEquals(1,reads.get());
        assertEquals(BUSY,cache.pending(50,false,()->BUSY));
    }

    @Test void nativeReadExceptionNeverBecomesCachedPermissionOrDenial() {
        var cache=new PlotHeatMapReadCache();
        assertThrows(IllegalStateException.class,()->cache.pending(50,false,()->{throw new IllegalStateException();}));
        assertEquals(BUSY,cache.pending(50,false,()->BUSY));
    }
}
