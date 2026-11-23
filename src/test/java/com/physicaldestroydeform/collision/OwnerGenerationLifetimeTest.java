package com.physicaldestroydeform.collision;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OwnerGenerationLifetimeTest {
    @Test void retirementStartsAtServerTickOfAbsenceNotWorldAgeOrLastContact() {
        var generation=new RealFixRuntimeBridge.Generation(7);
        assertFalse(generation.idleExpired(900_000));
        generation.setPresent(false,40);
        generation.setPresent(false,500);
        assertFalse(generation.idleExpired(640));
        assertTrue(generation.idleExpired(641));
    }

    @Test void reappearingOwnerCancelsRetirementAndSecondAbsenceGetsFreshGrace() {
        var generation=new RealFixRuntimeBridge.Generation(7);
        generation.setPresent(false,100);
        generation.setPresent(true,699);
        assertFalse(generation.idleExpired(1000));
        generation.setPresent(false,1000);
        assertFalse(generation.idleExpired(1600));
        assertTrue(generation.idleExpired(1601));
    }
}
