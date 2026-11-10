package com.physicaldestroydeform.collision;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GameImpactSettingsRegressionTest {
    @Test void scaleFixDeveloperGainIsBoundedAndOperatorOverridable() {
        String prior=System.getProperty("pdd.realfix.forceGain");
        try {
            System.clearProperty("pdd.realfix.forceGain");
            assertEquals(0.1,GameImpactCredit.Settings.fromSystemProperties().forceGain(),1e-12);
            System.setProperty("pdd.realfix.forceGain","0.25");
            assertEquals(0.25,GameImpactCredit.Settings.fromSystemProperties().forceGain(),1e-12);
            System.setProperty("pdd.realfix.forceGain","NaN");
            assertEquals(0.1,GameImpactCredit.Settings.fromSystemProperties().forceGain(),1e-12);
            System.setProperty("pdd.realfix.forceGain","2.0");
            assertEquals(0.1,GameImpactCredit.Settings.fromSystemProperties().forceGain(),1e-12);
            assertThrows(IllegalArgumentException.class,
                    ()->new GameImpactCredit.Settings(2,2,2,65536,2,4096,600));
        } finally {
            if(prior==null)System.clearProperty("pdd.realfix.forceGain");
            else System.setProperty("pdd.realfix.forceGain",prior);
        }
    }
    @Test void kOverrideRecomputesDefaultQCapButExplicitCapRemainsBinding() {
        String oldGain=System.getProperty("pdd.realfix.forceGain");
        String oldCap=System.getProperty("pdd.realfix.maxGroupQ");
        try {
            System.setProperty("pdd.realfix.forceGain","0.5");
            System.clearProperty("pdd.realfix.maxGroupQ");
            var override=GameImpactCredit.Settings.fromSystemProperties();
            assertEquals(0.5,override.forceGain());
            assertEquals(0.5*(GameImpactCredit.MAX_GAME_F-2),override.maxGroupQ(),1e-6);
            assertEquals(override.maxGroupQ(),override.candidate(1e20),1e-6);
            System.setProperty("pdd.realfix.maxGroupQ","1000");
            assertEquals(1000,GameImpactCredit.Settings.fromSystemProperties().candidate(1e20),1e-6);
        } finally {
            if(oldGain==null)System.clearProperty("pdd.realfix.forceGain");
            else System.setProperty("pdd.realfix.forceGain",oldGain);
            if(oldCap==null)System.clearProperty("pdd.realfix.maxGroupQ");
            else System.setProperty("pdd.realfix.maxGroupQ",oldCap);
        }
    }

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
