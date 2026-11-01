package com.physicaldestroydeform.collision;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GameImpactSettingsRegressionTest {
    @Test void oneSeparatedFrameCannotBypassPhysicalRearm() {
        String old = System.getProperty("pdd.realfix.separatedFrames");
        try {
            System.setProperty("pdd.realfix.separatedFrames", "1");
            assertTrue(GameImpactCredit.Settings.fromSystemProperties().separatedFrames() >= 2);
        } finally {
            if (old == null) System.clearProperty("pdd.realfix.separatedFrames");
            else System.setProperty("pdd.realfix.separatedFrames", old);
        }
    }
    @Test void invalidIdleTimeoutCannotCrashModInitialization() {
        String old = System.getProperty("pdd.realfix.maxIdleTicks");
        try {
            System.setProperty("pdd.realfix.maxIdleTicks", "0.01");
            assertTrue(GameImpactCredit.Settings.fromSystemProperties().maxIdleTicks() >= 1);
            System.setProperty("pdd.realfix.maxIdleTicks", "99999999999999999999");
            assertTrue(GameImpactCredit.Settings.fromSystemProperties().maxIdleTicks() >= 1);
        } finally {
            if (old == null) System.clearProperty("pdd.realfix.maxIdleTicks");
            else System.setProperty("pdd.realfix.maxIdleTicks", old);
        }
    }
}
