package com.physicaldestroydeform.stress;
import org.junit.jupiter.api.Test;
final class StressPropagationTest {
    @Test void diamond(){StressPropagationSelfTest.diamond();}
    @Test void cycle(){StressPropagationSelfTest.cycle();}
    @Test void elasticityOnce(){StressPropagationSelfTest.elasticityOnce();}
    @Test void budgetAndTtl(){StressPropagationSelfTest.budgetAndTtl();}
    @Test void ownerBoundary(){StressPropagationSelfTest.ownerBoundary();}
    @Test void stale(){StressPropagationSelfTest.stale();}
    @Test void minimumAfterMerge(){StressPropagationSelfTest.minimumAfterMerge();}
    @Test void subthresholdRootsMerge(){StressPropagationSelfTest.subthresholdRootsMerge();}
    @Test void subthresholdParentsMerge(){StressPropagationSelfTest.subthresholdParentsMerge();}
    @Test void direction(){StressPropagationSelfTest.direction();}
    @Test void deniedShare(){StressPropagationSelfTest.deniedShare();}
    @Test void stop(){StressPropagationSelfTest.stop();}
    @Test void nodeCap(){StressPropagationSelfTest.nodeCap();}
    @Test void rootIdentity(){StressPropagationSelfTest.rootIdentity();}
    @Test void unbreakable(){StressPropagationSelfTest.unbreakable();}
    @Test void permutationAndTickSlicing(){StressPropagationSelfTest.permutationAndTickSlicing();}
    @Test void randomGraphs(){StressPropagationSelfTest.randomGraphs();}
    @Test void invalidInputs(){StressPropagationSelfTest.invalidInputs();}
}
