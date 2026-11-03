package com.physicaldestroydeform.collision;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ForceCalibrationTest {
    @Test void observedQFixForcesSaturateWithoutChangingPolicy() {
        var stats=new ForceCalibration();
        var settings=GameImpactCredit.Settings.provisional();
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
        var settings=GameImpactCredit.Settings.provisional();
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
    @Test void resetRemovesOldRunCalibration() {
        var stats=new ForceCalibration();
        stats.record(100,GameImpactCredit.Settings.provisional());
        stats.clear();
        var s=stats.snapshot();
        assertEquals(0,s.approved());
        assertEquals(0,s.saturated());
        assertEquals(0,s.minRawQ());
        assertEquals(0,s.maxRawQ());
        assertEquals("[0, 0, 0, 0, 0, 0, 0, 0]",s.forceBuckets());
    }
}
