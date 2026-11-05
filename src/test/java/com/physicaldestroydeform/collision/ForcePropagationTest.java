package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.MaterialProperties;
import com.physicaldestroydeform.material.MaterialPlanner;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** 0.4.0 colFix: F-first propagation on either owner-local lattice. */
class ForcePropagationTest {
    private static final String WORLD="WORLD:minecraft:overworld";
    private static final String PLOT="PLOT:5069c055-4ebd-415c-b205-a6715b83156d";
    private static final GameImpactCredit.Settings FORCE=GameImpactCredit.Settings.provisional();
    private static final DamageModel.Settings DAMAGE=DamageModel.Settings.defaults();
    private static GameImpactCredit.Target at(String owner,int x,int y,int z) {
        return new GameImpactCredit.Target(owner,x+","+y+","+z,"minecraft:stone|Block{minecraft:stone}");
    }
    private static MaterialProperties material(int e) {return new MaterialProperties(40,100,20,e,false);}
    private static ForcePropagation.NeighborReader graph(Set<String> occupied) {
        return (from,x,y,z)->occupied.contains(from.ownerKey()+":"+x+","+y+","+z)
                ?ForcePropagation.Probe.present(at(from.ownerKey(),x,y,z))
                :ForcePropagation.Probe.empty();
    }
    private static ForcePropagation.Result line(String owner,int count) {
        var occupied=new HashSet<String>();
        for(int i=0;i<count;i++)occupied.add(owner+":"+i+",0,0");
        return ForcePropagation.plan(owner,List.of(at(owner,0,0,0)),100,graph(occupied),
                fp->material(0),FORCE,DAMAGE);
    }
    @Test void fiveBlockPathFHalvesAndTerminalTakesRemainderForWorldAndPlot() {
        for(String owner:List.of(WORLD,PLOT)) {
            var r=line(owner,5);
            assertEquals("VERIFIED_TERMINAL",r.endReason());
            assertEquals(5,r.shares().size());
            double[] f={50,25,12.5,6.25,6.25};
            double[] q={7.728,3.703,1.6905,.68425,.68425};
            for(int i=0;i<5;i++) {
                assertEquals(f[i],r.shares().get(i).allocatedF(),1e-9);
                assertEquals(q[i],r.shares().get(i).finalQ(),1e-9);
            }
            assertEquals(100,r.assignedF(),1e-9);
            assertEquals(0,r.unresolvedF(),1e-9);
            assertEquals(0,r.dampedF(),1e-9);
        }
    }
    @Test void singleAndTwoBlocksRespectProvenTerminal() {
        assertEquals(100,line(WORLD,1).shares().getFirst().allocatedF(),1e-9);
        var two=line(PLOT,2);
        assertEquals(50,two.shares().getFirst().allocatedF(),1e-9);
        assertEquals(50,two.shares().getLast().allocatedF(),1e-9);
    }
    @Test void middleElasticNodeReducesUpstreamQAndDownstreamForce() {
        var occupied=new HashSet<String>();
        for(int i=0;i<5;i++)occupied.add(WORLD+":"+i+",0,0");
        var r=ForcePropagation.plan(WORLD,List.of(at(WORLD,0,0,0)),100,graph(occupied),
                fp->material(0),FORCE,DAMAGE);
        // The shape/fingerprint is the material identity, not the coordinate; use distinct state evidence.
        var b=new ForcePropagation.NeighborReader() {
            public ForcePropagation.Probe read(GameImpactCredit.Target from,int x,int y,int z) {
                if(x<0||x>4||y!=0||z!=0)return ForcePropagation.Probe.empty();
                var t=new GameImpactCredit.Target(WORLD,x+",0,0",x==1?"minecraft:wool|elastic":"minecraft:stone|solid");
                return ForcePropagation.Probe.present(t);
            }
        };
        var seed=new GameImpactCredit.Target(WORLD,"0,0,0","minecraft:stone|solid");
        var elastic=ForcePropagation.plan(WORLD,List.of(seed),100,b,
                fp->material(fp.contains("elastic")?40:0),FORCE,DAMAGE);
        assertTrue(elastic.shares().getFirst().finalQ()<r.shares().getFirst().finalQ());
        assertTrue(elastic.shares().get(2).allocatedF()<r.shares().get(2).allocatedF());
        assertTrue(elastic.dampedQ()>0 && elastic.dampedF()>0);
        assertEquals(100,elastic.assignedF()+elastic.dampedF()+elastic.unresolvedF(),1e-8);
    }
    @Test void simultaneousContactsReceiveEqualSharesAndNeverRevisitDiamond() {
        var occupied=Set.of(WORLD+":0,0,0",WORLD+":1,0,0",WORLD+":0,0,1",WORLD+":1,0,1");
        var r=ForcePropagation.plan(WORLD,List.of(at(WORLD,1,0,0),at(WORLD,0,0,0)),100,
                graph(occupied),fp->material(0),FORCE,DAMAGE);
        assertEquals(4,r.shares().size());
        assertEquals(25,r.shares().get(0).allocatedF(),1e-9);
        assertEquals(25,r.shares().get(1).allocatedF(),1e-9);
        assertEquals(25,r.shares().get(2).allocatedF(),1e-9);
        assertEquals(25,r.shares().get(3).allocatedF(),1e-9);
        assertEquals(4L,r.shares().stream().map(t->t.target().localBlock()).distinct().count());
        assertEquals(100,r.assignedF(),1e-9);
    }
    @Test void unknownNeighborCannotPretendToBeLastBlock() {
        var r=ForcePropagation.plan(WORLD,List.of(at(WORLD,0,0,0)),100,
                (from,x,y,z)->x==1?ForcePropagation.Probe.unknown():ForcePropagation.Probe.empty(),
                fp->material(0),FORCE,DAMAGE);
        assertEquals("UNVERIFIED_BOUNDARY",r.endReason());
        assertEquals(50,r.assignedF(),1e-9);
        assertEquals(50,r.unresolvedF(),1e-9);
    }
    @Test void fingerprintConflictRejectsWholePlan() {
        assertThrows(IllegalArgumentException.class,()->ForcePropagation.plan(WORLD,
                List.of(at(WORLD,0,0,0),new GameImpactCredit.Target(WORLD,"0,0,0","changed")),
                100,graph(Set.of()),fp->material(0),FORCE,DAMAGE));
    }
    @Test void elasticHundredNeverCreatesNegativeOrInfiniteTransfers() {
        var provider=new ForcePropagation.NeighborReader() {
            public ForcePropagation.Probe read(GameImpactCredit.Target from,int x,int y,int z) {
                if(x<0||x>3||y!=0||z!=0)return ForcePropagation.Probe.empty();
                return ForcePropagation.Probe.present(new GameImpactCredit.Target(WORLD,x+",0,0",
                        x==1?"minecraft:wool|elastic":"minecraft:stone|solid"));
            }
        };
        var r=ForcePropagation.plan(WORLD,List.of(new GameImpactCredit.Target(WORLD,"0,0,0",
                "minecraft:stone|solid")),100,provider,
                fp->material(fp.contains("elastic")?100:0),FORCE,DAMAGE);
        assertEquals("VERIFIED_TERMINAL",r.endReason());
        assertEquals(4,r.shares().size());
        assertTrue(r.shares().getFirst().finalQ()>0);
        assertTrue(r.dampedF()>0 && r.dampedQ()>0);
        assertTrue(r.shares().stream().allMatch(t->Double.isFinite(t.finalQ())&&t.finalQ()>=0));
        assertEquals(100,r.assignedF()+r.dampedF()+r.unresolvedF(),1e-8);
    }
    @Test void boundedGraphNeverPassesLeftoverForceToArbitraryLastNode() {
        var occupied=new HashSet<String>();
        for(int i=0;i<200;i++)occupied.add(PLOT+":"+i+",0,0");
        var r=ForcePropagation.plan(PLOT,List.of(at(PLOT,0,0,0)),1e70,graph(occupied),
                fp->material(0),FORCE,DAMAGE);
        assertEquals(ForcePropagation.MAX_NODES_PER_SIDE,r.shares().size());
        assertEquals("UNVERIFIED_BOUNDARY",r.endReason());
        assertTrue(r.unresolvedF()>0);
        assertEquals(1e70,r.assignedF()+r.dampedF()+r.unresolvedF(),1e55);
    }
    @Test void sourceEnvelopeCapsTotalHighForceAfterPerBlockConversion() {
        var pair=GameImpactCredit.Pair.canonical("run","minecraft:overworld",
                new GameImpactCredit.Owner(WORLD,1),new GameImpactCredit.Owner(PLOT,1));
        var q=FORCE.candidate(1e10);
        var d=new GameImpactCredit.Decision(GameImpactCredit.Phase.ACTIVE,
                GameImpactCredit.Reason.APPROVED,1e10,q,new GameImpactCredit.Allocation(q/2,q/2,0),
                1,1,10,10,GameImpactCredit.POLICY_REVISION);
        var r=new ContactCreditLedger().issuePropagated(pair,d,
                ContactCreditLedger.Source.SABLE_POST_EFFECT,
                List.of(new ContactCreditLedger.Region(at(pair.a().stableKey(),0,0,0),
                        at(pair.b().stableKey(),0,0,0))),graph(Set.of()),fp->material(0),FORCE,DAMAGE);
        assertTrue(r.credited());
        assertEquals(q,r.allocatedA()+r.allocatedB(),1e-9);
        assertEquals(q,r.accounted(),1e-9);
    }

    @Test void integratedLedgerSeparatesWorldAndPlotAndDoesNotDoubleDiscountElasticity() {
        var pair=GameImpactCredit.Pair.canonical("run","minecraft:overworld",
                new GameImpactCredit.Owner(WORLD,1),new GameImpactCredit.Owner(PLOT,1));
        double q=FORCE.candidate(100);
        var d=new GameImpactCredit.Decision(GameImpactCredit.Phase.ACTIVE,
                GameImpactCredit.Reason.APPROVED,100,q,new GameImpactCredit.Allocation(q/2,q/2,0),
                1,1,10,10,GameImpactCredit.POLICY_REVISION);
        var evidence=List.of(new ContactCreditLedger.Region(at(pair.a().stableKey(),0,0,0),
                at(pair.b().stableKey(),0,0,0)));
        var ledger=new ContactCreditLedger();
        var r=ledger.issuePropagated(pair,d,ContactCreditLedger.Source.SABLE_POST_EFFECT,evidence,
                graph(Set.of(WORLD+":0,0,0",PLOT+":0,0,0")),fp->material(60),FORCE,DAMAGE);
        assertTrue(r.credited());assertTrue(r.propagated());
        assertEquals(1,r.a().size());assertEquals(1,r.b().size());
        assertEquals(pair.a().stableKey(),r.a().getFirst().target().ownerKey());
        assertEquals(pair.b().stableKey(),r.b().getFirst().target().ownerKey());
        assertEquals(q,r.accounted(),1e-9);
        var material=MaterialPlanner.plan(r,1,fp->material(60),DAMAGE);
        // colFix2 separates neighbor damping from the material's OWN elasticity.
        assertTrue(material.plans().getFirst().plan().activeQ()<r.a().getFirst().q());
        assertEquals(r.a().getFirst().q(),material.plans().getFirst().plan().accountedQ(),1e-8);
        assertEquals(ContactCreditLedger.Status.DUPLICATE_CAUSE,
                ledger.issuePropagated(pair,d,ContactCreditLedger.Source.SABLE_POST_EFFECT,evidence,
                        graph(Set.of()),fp->material(60),FORCE,DAMAGE).status());
    }
}
