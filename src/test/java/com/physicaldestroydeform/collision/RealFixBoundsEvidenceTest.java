package com.physicaldestroydeform.collision;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.physicaldestroydeform.collision.RealFixBoundsEvidence.Box;

final class RealFixBoundsEvidenceTest {
    @Test void broadPhaseSeparationAndApproachHaveDifferentSemantics() {
        Box a=new Box(0,0,0,1,1,1),far=new Box(2,0,0,3,1,1);
        Box near=new Box(1.5,0,0,2.5,1,1);
        assertTrue(a.separatedFrom(far));
        assertTrue(a.separatedFrom(near));
        assertTrue(RealFixBoundsEvidence.approaching(a,far,a,near));
        assertFalse(RealFixBoundsEvidence.approaching(a,near,a,far));
        assertFalse(a.separatedFrom(new Box(1,0,0,2,1,1)));
    }
    @Test void worldBlockBoundsAndBadValuesAreHandled() {
        assertEquals(1.0,new Box(0,0,0,1,1,1).gap(Box.block(2,0,0)),1e-12);
        assertThrows(IllegalArgumentException.class,()->new Box(0,0,0,Double.NaN,1,1));
        assertThrows(IllegalArgumentException.class,()->new Box(1,0,0,0,1,1));
    }
    @Test void worldSeparationRequiresIndependentClearanceProof() {
        var a=RealFixBoundsEvidence.Box.block(0,0,0);
        var staleWorldBlock=RealFixBoundsEvidence.Box.block(20,0,0);
        assertTrue(RealFixBoundsEvidence.separatedForRearm(false,a,staleWorldBlock,false));
        assertFalse(RealFixBoundsEvidence.separatedForRearm(true,a,staleWorldBlock,false));
        assertTrue(RealFixBoundsEvidence.separatedForRearm(true,a,staleWorldBlock,true));
        assertFalse(RealFixBoundsEvidence.separatedForRearm(true,null,staleWorldBlock,false));
    }
}
