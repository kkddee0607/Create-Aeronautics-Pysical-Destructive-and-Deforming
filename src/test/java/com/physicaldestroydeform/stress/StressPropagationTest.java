package com.physicaldestroydeform.stress;
import org.junit.jupiter.api.Test;
final class StressPropagationTest {
    @Test void diamond(){StressPropagationSelfTest.diamond();}
    @Test void cycle(){StressPropagationSelfTest.cycle();}
    @Test void elasticityOnce(){StressPropagationSelfTest.elasticityOnce();}
    @Test void budgetAndTtl(){StressPropagationSelfTest.budgetAndTtl();}
    @Test void independentRootWait(){StressPropagationSelfTest.independentRootWait();}
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
    @Test void rootReentryDiagnosticPreservesOriginalLedger(){
        var m=new com.physicaldestroydeform.MaterialProperties(0,1024,0,0,false);
        var a=new StressPropagation.Node(new StressPropagation.Cell("WORLD:test:1",0,0,0),"stone","rev0",m);
        var b=new StressPropagation.Node(a.cell().offset(1,0,0),"stone","rev0",m);
        var s=new StressPropagation.Session(java.util.List.of(new StressPropagation.Seed(a,100,null),new StressPropagation.Seed(b,50,null)),
                StressPropagation.Policy.defaults(),com.physicaldestroydeform.DamageModel.Settings.defaults(),0);
        s.advance(c->c.equals(a.cell())?StressPropagation.Probe.solid(a):c.equals(b.cell())?StressPropagation.Probe.solid(b):StressPropagation.Probe.empty(),0,512);
        org.junit.jupiter.api.Assertions.assertTrue(s.ledger().finished());
        org.junit.jupiter.api.Assertions.assertEquals(150d,s.ledger().reasons().get(StressPropagation.Unused.LOOP_OR_LATE_UNUSED),1e-9);
        org.junit.jupiter.api.Assertions.assertEquals(150d,s.unusedBreakdown().get(StressPropagation.UnusedDetail.ROOT_REENTRY),1e-9);
        org.junit.jupiter.api.Assertions.assertTrue(s.plans().isEmpty());
    }
}
