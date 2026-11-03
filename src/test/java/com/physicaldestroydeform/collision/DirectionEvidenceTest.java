package com.physicaldestroydeform.collision;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DirectionEvidenceTest {
    @Test void verifiedWitnessAcceptsOneAxisWithExplicitFrameEpochAndQuality() {
        var d=new DirectionEvidence("PLOT_LOCAL","SABLE_PRE_SOLVER","VERIFIED",120,3,4,0,-1,0);
        assertEquals(-1,d.signedY());assertEquals(4,d.transformEpoch());
        assertEquals(3,d.sourceSubstep());assertEquals("VERIFIED",d.sourceQuality());
    }
    @Test void unsignedOrMultipleAxesDoNotProduceFalseVerifiedDirection() {
        assertThrows(IllegalArgumentException.class,()->new DirectionEvidence("WORLD","unknown","UNKNOWN",1,0,1,0,0,0));
        assertThrows(IllegalArgumentException.class,()->new DirectionEvidence("WORLD","unknown","UNKNOWN",1,0,1,1,1,0));
        assertThrows(IllegalArgumentException.class,()->new DirectionEvidence("WORLD","unknown","UNKNOWN",-1,0,1,1,0,0));
    }
}
