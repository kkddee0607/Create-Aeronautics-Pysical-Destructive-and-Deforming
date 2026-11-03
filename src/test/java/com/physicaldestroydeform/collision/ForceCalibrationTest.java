package com.physicaldestroydeform.collision;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ForceCalibrationTest {
    @Test void observedQFixForcesSaturateWithoutChangingPolicy() {
        var stats=new ForceCalibration();
        var settings=new GameImpactCredit.Settings(2,2,0.25,64,2,4096,600);
        for(double force:new double[]{57431.21875,18939.27734375,34964.3203125}) {
            stats.record(force,settings);
            assertEquals(64,settings.candidate(force));
        }
        var s=stats.snapshot();
        assertEquals(3,s.approved());
        assertEquals(3,s.saturated());
        assertEquals(18939.27734375,s.minPeak());
        assertEquals(57431.21875,s.maxPeak());
        assertTrue(s.minRawQ()>64);
        assertTrue(s.maxRawQ()>s.minRawQ());
    }
    @Test void boundaryAndUnapprovedInputsCannotCreateSamples() {
        var stats=new ForceCalibration();
        var settings=new GameImpactCredit.Settings(2,2,0.25,64,2,4096,600);
        stats.record(Double.NaN,settings);
        stats.record(-1,settings);
        stats.record(5,null);
        assertEquals(0,stats.snapshot().approved());
        stats.record(257,settings);
        assertEquals(1,stats.snapshot().approved());
        assertEquals(0,stats.snapshot().saturated());
        stats.record(258,settings);
        assertEquals(1,stats.snapshot().saturated());
        assertEquals(2,stats.snapshot().approved());
    }
    @Test void newDefaultUsesObservedForceRangeWithoutEarlySaturation() {
        var stats=new ForceCalibration();
        var settings=GameImpactCredit.Settings.provisional();
        for(double peak:new double[]{3849.28271484375,30181.88671875,52421.02,
                51475.45,30462.21,32265.78}) {
            stats.record(peak,settings);
            assertTrue(settings.candidate(peak)>0);
            assertTrue(settings.candidate(peak)<64);
        }
        assertEquals(6,stats.snapshot().approved());
        assertEquals(0,stats.snapshot().saturated());
        assertEquals(3849.28271484375,stats.snapshot().minPeak());
        assertEquals(52421.02,stats.snapshot().maxPeak());
    }
    @Test void resetRemovesOldRunCalibration() {
        var stats=new ForceCalibration();
        stats.record(100,new GameImpactCredit.Settings(2,2,0.25,64,2,4096,600));
        stats.clear();
        var s=stats.snapshot();
        assertEquals(0,s.approved());
        assertEquals(0,s.saturated());
        assertEquals(0,s.minRawQ());
        assertEquals(0,s.maxRawQ());
        assertEquals("[0, 0, 0, 0, 0, 0, 0, 0]",s.forceBuckets());
    }
}
