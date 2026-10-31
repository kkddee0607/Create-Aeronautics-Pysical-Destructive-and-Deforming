package com.physicaldestroydeform.diagnostics;

import com.physicaldestroydeform.collision.ImpactEpisodeLedger;
import com.physicaldestroydeform.collision.ImpactMath;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WhyRejectDiagnosticsTest {
    private static final String DIM="minecraft:overworld";
    private static final String BALLOON="PLOT:balloon", STONE="PLOT:stone";
    private static final WhyRejectDiagnostics.Target CHAIN=new WhyRejectDiagnostics.Target(
            "SABLE_PLOT","balloon",1,60,1,"minecraft:chain");
    private static final WhyRejectDiagnostics.Target DEEPSLATE=new WhyRejectDiagnostics.Target(
            "SABLE_PLOT","balloon",2,60,1,"minecraft:reinforced_deepslate");
    private static final WhyRejectDiagnostics.Target PLOT_STONE=new WhyRejectDiagnostics.Target(
            "SABLE_PLOT","stone",40,65,40,"minecraft:stone");
    private static final WhyRejectDiagnostics.Target WORLD_STONE=new WhyRejectDiagnostics.Target(
            "STATIC_WORLD",null,42,65,40,"minecraft:stone");
    private static ImpactEpisodeLedger.Key key(long epoch){
        return ImpactEpisodeLedger.Key.of(DIM,epoch,BALLOON,STONE);
    }
    @Test void distinguishesPlotStoneFromWorldStoneAndCountsAllObservedCallbacks() {
        var report=new WhyRejectDiagnostics();
        report.observe(key(11),CHAIN,PLOT_STONE,ImpactMath.Reason.DIRECTION_UNVERIFIED,
                "CONFLICTING_MANIFOLD_NORMAL");
        report.observe(key(11),CHAIN,PLOT_STONE,ImpactMath.Reason.DIRECTION_UNVERIFIED,
                "CONFLICTING_MANIFOLD_NORMAL");
        report.observe(key(11),DEEPSLATE,WORLD_STONE,ImpactMath.Reason.DIRECTION_UNVERIFIED,
                "NOT_PLOT_PLOT");
        assertEquals(2,report.focusCount("chainPlotStone"));
        assertEquals(0,report.focusCount("reinforcedPlotStone"));
        var event=report.finish(key(11),ImpactMath.Result.reject(ImpactMath.Reason.DIRECTION_UNVERIFIED));
        assertEquals(3,event.callbacks());
        assertEquals(2,event.distinctPairs());
        assertTrue(event.pairs().contains("minecraft:chain"));
        assertTrue(event.pairs().contains("minecraft:reinforced_deepslate"));
        assertTrue(event.pairs().contains("CONFLICTING_MANIFOLD_NORMAL"));
        assertEquals(2,report.pairTypeCount());
        assertEquals(1,report.allEpisodes());
        assertEquals(0,report.allApproved());
    }
    @Test void reportsApprovedOwnerPairWithoutClaimingSpecificBlockContactEnergy() {
        var report=new WhyRejectDiagnostics();
        report.observe(key(12),CHAIN,PLOT_STONE,ImpactMath.Reason.APPROVED,"OPPOSING_NORMAL_VALIDATED_BODY_PAIR_TICK");
        report.observe(key(12),DEEPSLATE,PLOT_STONE,ImpactMath.Reason.DUPLICATE_EPISODE,
                "OPPOSING_NORMAL_VALIDATED_BODY_PAIR_TICK");
        var result=new ImpactMath.Result(ImpactMath.Kind.CONSERVATIVE_PROXY,
                ImpactMath.Reason.APPROVED,4,2,2,"NATIVE_PLOT_PAIR_TEST");
        var event=report.finish(key(12),result);
        assertTrue(event.approved());
        assertEquals(1,report.allApproved());
        assertEquals(1,report.focusCount("chainPlotStone"));
        assertEquals(1,report.focusCount("reinforcedPlotStone"));
        assertEquals(2,report.focusTypes().size());
        assertTrue(report.focusTypes().stream().allMatch(s->s.contains("approvedOwnerEpisodesAssociated=1")));
    }
    @Test void preservesAllReasonsAndExplicitlyCountsNoOtherAndOwnerFailure() {
        var report=new WhyRejectDiagnostics();
        report.unverified(false,"VERIFIED","NO_TARGET");
        report.unverified(true,"BLOCK_MISMATCH","VERIFIED");
        report.observe(key(13),CHAIN,PLOT_STONE,ImpactMath.Reason.ROTATION_UNRESOLVED,"BAD_NORMAL");
        report.observe(key(13),CHAIN,PLOT_STONE,ImpactMath.Reason.DIRECTION_UNVERIFIED,"BAD_NORMAL");
        var event=report.finish(key(13),ImpactMath.Result.reject(ImpactMath.Reason.ROTATION_UNRESOLVED));
        assertEquals(1,report.missingOther());
        assertEquals(1,report.unverifiedTargets());
        assertEquals(1L,report.unverifiedReasons().get("BLOCK_MISMATCH/VERIFIED"));
        assertTrue(event.pairs().contains("ROTATION_UNRESOLVED=1"));
        assertTrue(event.pairs().contains("DIRECTION_UNVERIFIED=1"));
    }
    @Test void sessionSummaryDoesNotHideZeroTargetAndSuppressionIsVisible() {
        var report=new WhyRejectDiagnostics();
        report.observe(key(14),CHAIN,WORLD_STONE,ImpactMath.Reason.DIRECTION_UNVERIFIED,"WORLD_FACE:NOT_FULL_CUBE");
        report.finish(key(14),ImpactMath.Result.reject(ImpactMath.Reason.DIRECTION_UNVERIFIED));
        report.suppressEpisodeLine();
        assertEquals(0,report.focusCount("chainPlotStone"));
        assertEquals(1,report.episodeLinesSuppressed());
        assertEquals(1,report.observedCallbacks());
        assertEquals(1,report.allEpisodes());
    }
    @Test void excessiveUniquePairsAreCountedNotMistakenForZero() {
        var report=new WhyRejectDiagnostics();
        for(int i=0;i<WhyRejectDiagnostics.MAX_TARGET_PAIRS_PER_EPISODE+5;i++) {
            var target=new WhyRejectDiagnostics.Target("SABLE_PLOT","balloon",i,63,1,"minecraft:chain");
            report.observe(key(15),target,PLOT_STONE,ImpactMath.Reason.DIRECTION_UNVERIFIED,
                    "NO_NATIVE_EFFECT_FOR_BODY_PAIR_TICK");
        }
        var event=report.finish(key(15),ImpactMath.Result.reject(ImpactMath.Reason.DIRECTION_UNVERIFIED));
        assertEquals(WhyRejectDiagnostics.MAX_TARGET_PAIRS_PER_EPISODE,event.distinctPairs());
        assertEquals(5,event.unlistedPairs());
        assertEquals(5,report.unlistedPairSamples());
        assertEquals(WhyRejectDiagnostics.MAX_TARGET_PAIRS_PER_EPISODE+5,report.focusCount("chainPlotStone"));
    }
    @Test void missingEpisodeIsAnInternalErrorNotSilentApproval() {
        var report=new WhyRejectDiagnostics();
        assertThrows(IllegalStateException.class,()->report.finish(key(99),
                ImpactMath.Result.reject(ImpactMath.Reason.NO_APPROVED_BUDGET)));
    }
    @Test void canonicalBodyIdsAndServerTickSurviveReciprocalCallbacks() {
        var report=new WhyRejectDiagnostics();
        report.observe(key(20),500,"1/2",CHAIN,PLOT_STONE,
                ImpactMath.Reason.DIRECTION_UNVERIFIED,"NO_NATIVE_EFFECT_FOR_BODY_PAIR_TICK",()->"firstSnapshot");
        report.observe(key(20),500,"1/2",PLOT_STONE,CHAIN,
                ImpactMath.Reason.DIRECTION_UNVERIFIED,"NO_NATIVE_EFFECT_FOR_BODY_PAIR_TICK",()->"reverseSnapshot");
        var event=report.finish(key(20),ImpactMath.Result.reject(ImpactMath.Reason.DIRECTION_UNVERIFIED));
        assertEquals(500L,event.serverTick());
        assertEquals("1/2",event.nativeRuntimeIds());
        assertEquals(2,event.distinctPairs());
        assertTrue(event.pairs().contains("firstSnapshot"));
        assertTrue(event.pairs().contains("reverseSnapshot"));
        assertFalse(event.pairs().contains("MIXED_RUNTIME_OR_TICK"));
    }
    @Test void expensiveKinematicEvidenceIsLazyAndOnlyRecordedForFirstPairInstance() {
        var report=new WhyRejectDiagnostics();
        var count=new java.util.concurrent.atomic.AtomicInteger();
        for(int i=0;i<100;i++)report.observe(key(21),501,"1/2",CHAIN,PLOT_STONE,
                ImpactMath.Reason.DIRECTION_UNVERIFIED,"CONFLICTING_MANIFOLD_NORMAL",
                ()->{count.incrementAndGet();return "normal=null mass=10";});
        assertEquals(1,count.get());
        var event=report.finish(key(21),ImpactMath.Result.reject(ImpactMath.Reason.DIRECTION_UNVERIFIED));
        assertEquals(100,event.callbacks());
        assertEquals(1,event.distinctPairs());
        assertTrue(event.pairs().contains("firstPreSolverEvidence=normal=null mass=10"));
    }
}
