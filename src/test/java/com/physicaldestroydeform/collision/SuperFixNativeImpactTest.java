package com.physicaldestroydeform.collision;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class SuperFixNativeImpactTest {
    @Test void largeShipSearchIsNarrowOnlyWhenAnchoredToNativeWorldPoint() {
        var big=new RealFixBoundsEvidence.Box(0,0,0,40,20,40);
        assertEquals(RealFixBoundaryCandidates.Reason.TOO_BROAD,
                RealFixBoundaryCandidates.nearOverlap(big,1024).reason());
        var r=RealFixBoundaryCandidates.nearPlanePoint(big,12.5,10.5,18.5,32);
        assertEquals(RealFixBoundaryCandidates.Reason.CANDIDATES,r.reason());
        assertEquals(1,r.blocks().size());
        assertEquals(new RealFixBoundaryCandidates.Block(12,10,18),r.blocks().getFirst());
        assertEquals(RealFixBoundaryCandidates.Reason.NO_SWEPT_BOUNDS,
                RealFixBoundaryCandidates.nearPlanePoint(big,9999,10,18,32).reason());
    }
    @Test void identicalNumericBlockCoordsAreDistinctAcrossWorldAndPlot() {
        var world=new GameImpactCredit.Target("WORLD:minecraft:overworld","5,64,5","minecraft:stone");
        var plot=new GameImpactCredit.Target("PLOT:aaa","5,64,5","minecraft:stone");
        assertNotEquals(world,plot);
    }
    @Test void canonicalOwnerOrderDoesNotSwapWorldAndPlotBounds() {
        var plot=new GameImpactCredit.Owner("PLOT:aaa",1);
        var world=new GameImpactCredit.Owner("WORLD:minecraft:overworld",1);
        var pair=GameImpactCredit.Pair.canonical("run","minecraft:overworld",world,plot);
        var plotBox=RealFixBoundsEvidence.Box.block(1,2,3);
        var bounds=RealFixRuntimeBridge.alignBounds(pair,world.stableKey(),null,plotBox);
        assertNotNull(bounds);
        assertSame(plotBox,bounds[0]);
        assertNull(bounds[1]);
        assertNull(RealFixRuntimeBridge.alignBounds(pair,"UNKNOWN",null,plotBox));
    }
    @Test void preContactClearanceRetainsTrackingBeyondSixHundredTicks() {
        // superFix lost the tracked AABB/evidence at tick~600 because lastSeen=-1,
        // although a verified precontact sample continued through tick 897.
        assertFalse(RealFixRuntimeBridge.trackedIdleExpired(903,897));
        assertFalse(RealFixRuntimeBridge.trackedIdleExpired(903,903));
        assertTrue(RealFixRuntimeBridge.trackedIdleExpired(903,302));
        var policy=new GameImpactCredit(GameImpactCredit.Settings.provisional());
        var pair=GameImpactCredit.Pair.canonical("run","minecraft:overworld",
                new GameImpactCredit.Owner("PLOT:B1",1),new GameImpactCredit.Owner("PLOT:B2",1));
        policy.step(new GameImpactCredit.Frame(pair,1,List.of(),true,false,null,null));
        for(long tick=2;tick<=897;tick++)
            policy.step(new GameImpactCredit.Frame(pair,tick,List.of(),true,false,null,null));
        assertEquals(GameImpactCredit.Phase.ARMED,policy.phase(pair));
        var hit=policy.step(new GameImpactCredit.Frame(pair,903,
                List.of(new GameImpactCredit.ObservedForce(2,5,39733.21875,0)),false,true,
                new GameImpactCredit.Target(pair.a().stableKey(),"1,0,0","stone"),
                new GameImpactCredit.Target(pair.b().stableKey(),"1,2,0","stone")));
        assertTrue(hit.credited());
        assertEquals(39.73121875,hit.issuedQ(),1e-9);
        assertEquals(GameImpactCredit.Reason.DUPLICATE_EPISODE,
                policy.step(new GameImpactCredit.Frame(pair,904,
                        List.of(new GameImpactCredit.ObservedForce(2,5,52000,0)),false,false,
                        null,null)).reason());
    }
    @Test void preSeparatedFrameWarmupOnlyOnceAndRestNeverRegeneratesCredit() {
        var policy=new GameImpactCredit(new GameImpactCredit.Settings(2,2,0.25,64,2,4096,600));
        var a=new GameImpactCredit.Owner("PLOT:aaa",1);
        var b=new GameImpactCredit.Owner("WORLD:minecraft:overworld",1);
        var pair=GameImpactCredit.Pair.canonical("run","minecraft:overworld",a,b);
        var targetA=new GameImpactCredit.Target(pair.a().stableKey(),"512,64,1024","minecraft:stone|stable");
        var f=new GameImpactCredit.ObservedForce(7,-1,42,0);
        assertEquals(GameImpactCredit.Phase.UNINITIALIZED,
                policy.step(new GameImpactCredit.Frame(pair,1,List.of(),true,false,null,null)).phase());
        assertEquals(GameImpactCredit.Phase.ARMED,
                policy.step(new GameImpactCredit.Frame(pair,2,List.of(),true,false,null,null)).phase());
        var result=policy.step(new GameImpactCredit.Frame(pair,3,List.of(f),false,true,targetA,null));
        assertTrue(result.credited());
        assertEquals(10,result.issuedQ());
        assertEquals(5,result.allocation().sideA());
        assertEquals(5,result.allocation().unused());
        for(int tick=4;tick<104;tick++) {
            var rest=policy.step(new GameImpactCredit.Frame(pair,tick,List.of(f),false,false,targetA,null));
            assertFalse(rest.credited());
        }
    }
}
