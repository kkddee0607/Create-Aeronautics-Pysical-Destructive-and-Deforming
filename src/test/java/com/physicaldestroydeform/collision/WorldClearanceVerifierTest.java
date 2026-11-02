package com.physicaldestroydeform.collision;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.physicaldestroydeform.collision.GameImpactCredit.*;
import com.physicaldestroydeform.collision.RealFixBoundsEvidence.Box;

final class WorldClearanceVerifierTest {
    private static final Pair PAIR=Pair.canonical("run","minecraft:overworld",
            new Owner("PLOT:ship",1),new Owner("WORLD:minecraft:overworld",1));
    private static final Pair OTHER_GENERATION=Pair.canonical("run","minecraft:overworld",
            new Owner("PLOT:ship",2),new Owner("WORLD:minecraft:overworld",1));
    private static final WorldClearanceVerifier.Inspector EMPTY=(x,y,z)->WorldClearanceVerifier.Cell.EMPTY;

    @Test void largeEmptyPlotCompletesAcrossTicksWithoutR4Limit() {
        var verifier=new WorldClearanceVerifier();
        Box box=new Box(0,60,0,10,65,8);
        long total=-1;
        int steps=0;
        WorldClearanceVerifier.Result result;
        do {
            result=verifier.inspect(PAIR,box,++steps,new int[]{128},EMPTY);
            total=result.total();
            if(steps==1)assertEquals(WorldClearanceVerifier.Status.IN_PROGRESS,result.status());
            assertTrue(steps<100,"bounded incremental scan failed to converge");
        } while(!result.proved());
        assertTrue(total>32);
        assertEquals(total,result.checked());
        assertEquals(0,verifier.pending());
    }
    @Test void poseChangeRejectsPriorScannedRegion() {
        var verifier=new WorldClearanceVerifier();
        Box first=new Box(0,0,0,10,5,5), moved=new Box(2,0,0,12,5,5);
        var partial=verifier.inspect(PAIR,first,1,new int[]{64},EMPTY);
        assertEquals(64,partial.checked());
        var changed=verifier.inspect(PAIR,moved,2,new int[]{64},EMPTY);
        assertEquals(WorldClearanceVerifier.Status.INVALIDATED,changed.status());
        assertEquals(64,changed.checked());
        assertTrue(changed.total()>64);
    }
    @Test void actualContactKillsPartialScanAndDoesNotRearm() {
        var verifier=new WorldClearanceVerifier();
        Box box=new Box(0,0,0,10,6,8);
        var initial=verifier.inspect(PAIR,box,1,new int[]{64},EMPTY);
        assertEquals(64,initial.checked());
        verifier.contact(PAIR);
        var restarted=verifier.inspect(PAIR,box,2,new int[]{64},EMPTY);
        assertEquals(64,restarted.checked());
        assertFalse(restarted.proved());
    }
    @Test void unloadedAndBlockingCellsNeverProveClearance() {
        var verifier=new WorldClearanceVerifier();
        Box box=new Box(0,0,0,7,3,3);
        assertEquals(WorldClearanceVerifier.Status.UNAVAILABLE,
                verifier.inspect(PAIR,box,1,new int[]{64},(x,y,z)->x==0
                        ?WorldClearanceVerifier.Cell.UNAVAILABLE:WorldClearanceVerifier.Cell.EMPTY).status());
        assertEquals(WorldClearanceVerifier.Status.BLOCKED,
                verifier.inspect(PAIR,box,2,new int[]{64},(x,y,z)->x==0
                        ?WorldClearanceVerifier.Cell.COLLIDER:WorldClearanceVerifier.Cell.EMPTY).status());
        assertEquals(WorldClearanceVerifier.Status.IN_PROGRESS,
                verifier.inspect(PAIR,box,3,new int[]{64},EMPTY).status());
    }
    @Test void zeroBudgetAndSameTickCannotManufactureSecondSeparation() {
        var verifier=new WorldClearanceVerifier();
        Box box=new Box(0,0,0,6,4,4);
        var a=verifier.inspect(PAIR,box,1,new int[]{0},EMPTY);
        var b=verifier.inspect(PAIR,box,1,new int[]{1024},EMPTY);
        assertEquals(WorldClearanceVerifier.Status.IN_PROGRESS,a.status());
        assertEquals(a,b);
        assertEquals(0,a.checked());
    }
    @Test void worldEditBetweenPartialScansIsCaughtByAtomicConfirmation() {
        var verifier=new WorldClearanceVerifier();
        var box=new Box(0,0,0,10,4,4);
        var altered=new boolean[]{false};
        var inspector=(WorldClearanceVerifier.Inspector)(x,y,z)->
                altered[0] && x==-1 && y==-1 && z==-1
                        ?WorldClearanceVerifier.Cell.COLLIDER:WorldClearanceVerifier.Cell.EMPTY;
        assertEquals(WorldClearanceVerifier.Status.IN_PROGRESS,
                verifier.inspect(PAIR,box,1,new int[]{64},inspector).status());
        altered[0]=true;
        WorldClearanceVerifier.Result result;
        int tick=1;
        do { result=verifier.inspect(PAIR,box,++tick,new int[]{64},inspector); }
        while(result.status()==WorldClearanceVerifier.Status.IN_PROGRESS && tick<100);
        assertEquals(WorldClearanceVerifier.Status.BLOCKED,result.status());
        assertEquals("CONFIRM_COLLIDER_FOUND",result.reason());
    }
    @Test void atomicConfirmationWaitsForServerWideBudget() {
        var verifier=new WorldClearanceVerifier();
        var box=new Box(0,0,0,8,5,5);
        var first=verifier.inspect(PAIR,box,1,new int[]{64},new int[]{0},EMPTY);
        assertEquals(WorldClearanceVerifier.Status.IN_PROGRESS,first.status());
        WorldClearanceVerifier.Result result=first;
        int tick=1;
        while(result.checked()<result.total() && tick<50)
            result=verifier.inspect(PAIR,box,++tick,new int[]{64},new int[]{0},EMPTY);
        assertEquals("WAIT_ATOMIC_CONFIRM_BUDGET",result.reason());
        assertEquals(WorldClearanceVerifier.Status.CLEAR,
                verifier.inspect(PAIR,box,++tick,new int[]{0},
                        new int[]{WorldClearanceVerifier.MAX_CONFIRM_PER_TICK},EMPTY).status());
    }
    @Test void oversizedAabbFailsClosedWithoutScanning() {
        var verifier=new WorldClearanceVerifier();
        var result=verifier.inspect(PAIR,new Box(0,0,0,50,20,30),1,new int[]{1024},EMPTY);
        assertEquals(WorldClearanceVerifier.Status.TOO_BROAD,result.status());
        assertEquals(0,verifier.pending());
    }
    @Test void minorPoseJitterKeepingSameGridDoesNotRestart() {
        var verifier=new WorldClearanceVerifier();
        var first=new Box(0.2,0.2,0.2,9.3,4.3,4.3);
        var shifted=new Box(0.22,0.2,0.2,9.32,4.3,4.3);
        var a=verifier.inspect(PAIR,first,1,new int[]{64},EMPTY);
        var b=verifier.inspect(PAIR,shifted,2,new int[]{64},EMPTY);
        assertEquals(WorldClearanceVerifier.Status.IN_PROGRESS,b.status());
        assertEquals(128,b.checked());
    }
    @Test void completedScanCanConfirmAgainOnTheVeryNextTick() {
        var verifier=new WorldClearanceVerifier();
        Box box=new Box(0,0,0,8,5,5);
        WorldClearanceVerifier.Result result=null;
        int tick=0;
        do { result=verifier.inspect(PAIR,box,++tick,new int[]{64},EMPTY); }
        while(!result.proved() && tick<100);
        assertTrue(result.proved());
        var again=verifier.inspect(PAIR,box,++tick,new int[]{0},
                new int[]{WorldClearanceVerifier.MAX_CONFIRM_PER_TICK},EMPTY);
        assertEquals(WorldClearanceVerifier.Status.CLEAR,again.status());
        assertEquals(again,verifier.inspect(PAIR,box,tick,new int[]{0},EMPTY));
    }
    @Test void generationsAndResetAreIndependent() {
        var verifier=new WorldClearanceVerifier();
        Box box=new Box(0,0,0,7,3,3);
        verifier.inspect(PAIR,box,1,new int[]{32},EMPTY);
        verifier.inspect(OTHER_GENERATION,box,1,new int[]{32},EMPTY);
        assertEquals(2,verifier.pending());
        verifier.invalidateOwner("PLOT:ship");
        assertEquals(0,verifier.pending());
    }
    @Test void consecutiveCompleteClearancesIssueIndependentEpisodeCredits() {
        var verifier=new WorldClearanceVerifier();
        var credit=new GameImpactCredit(Settings.provisional());
        var ledger=new ContactCreditLedger();
        Box tiny=new Box(0.2,5.2,0.2,0.8,5.8,0.8);
        for(int tick=1;tick<=2;tick++) {
            assertTrue(verifier.inspect(PAIR,tiny,tick,new int[]{64},EMPTY).proved());
            credit.step(new Frame(PAIR,tick,List.of(),true,false,null,null));
        }
        var target=new Target(PAIR.a().stableKey(),"1,1,1","minecraft:stone");
        var force=List.of(new ObservedForce(-1,1,40,0));
        var first=credit.step(new Frame(PAIR,3,force,false,true,target,null));
        assertTrue(ledger.issue(PAIR,first,ContactCreditLedger.Source.SABLE_POST_EFFECT,
                List.of(new ContactCreditLedger.Region(target,null))).credited());
        assertEquals(Reason.DUPLICATE_EPISODE,
                credit.step(new Frame(PAIR,4,force,false,true,target,null)).reason());
        for(int tick=5;tick<=6;tick++) {
            assertTrue(verifier.inspect(PAIR,tiny,tick,new int[]{64},EMPTY).proved());
            credit.step(new Frame(PAIR,tick,List.of(),true,false,null,null));
        }
        var second=credit.step(new Frame(PAIR,7,force,false,true,target,null));
        var issued=ledger.issue(PAIR,second,ContactCreditLedger.Source.SABLE_POST_EFFECT,
                List.of(new ContactCreditLedger.Region(target,null)));
        assertTrue(issued.credited());
        assertNotEquals(first.episodeGeneration(),second.episodeGeneration());
        assertEquals(first.issuedQ(),second.issuedQ());
    }
}
