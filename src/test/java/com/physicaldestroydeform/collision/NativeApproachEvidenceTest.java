package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.collision.FrameMath.Pose;
import com.physicaldestroydeform.collision.FrameMath.Vec;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent pre-solver evidence; native post-effect peak alone cannot approve Q. */
final class NativeApproachEvidenceTest {
    private static final String DIM="minecraft:overworld";
    private static final UUID PLOT=UUID.fromString("5069c055-4ebd-415c-b205-a6715b83156d");
    private static final GameImpactCredit.Pair PAIR=GameImpactCredit.Pair.canonical("r",DIM,
            new GameImpactCredit.Owner("PLOT:"+PLOT,1),
            new GameImpactCredit.Owner("WORLD:"+DIM,1));
    private static final Pose POSE=new Pose(new Vec(0,0,0),new Vec(0,0,0),
            new Vec(1,1,1),0,0,0,1);
    private static CollisionFrame.Snapshot frame(long tick,double vx) {
        var body=new CollisionFrame.Body(PLOT,0,10,0,10,POSE,new Vec(vx,0,0),
                new Vec(0,0,0),new Vec(0,0,0),true);
        return new CollisionFrame.Snapshot(1,tick,DIM,CollisionFrame.Phase.PRE_SOLVER,
                List.of(body),true);
    }
    private static NativeEffectDirection.Result normal() {
        return new NativeEffectDirection.Result(Map.of(PAIR.a().stableKey(),
                new ForcePropagation.Orientation(new Vec(-1,0,0),"NATIVE"),
                PAIR.b().stableKey(),new ForcePropagation.Orientation(new Vec(1,0,0),"NATIVE")),"TEST",1);
    }
    @Test void movingPlotIntoWorldCanApproveButRestingAndRecedingCannot() {
        assertTrue(NativeApproachEvidence.approaching(PAIR,List.of(frame(7,3)),normal(),7));
        assertFalse(NativeApproachEvidence.approaching(PAIR,List.of(frame(7,0)),normal(),7));
        assertFalse(NativeApproachEvidence.approaching(PAIR,List.of(frame(7,-3)),normal(),7));
    }
    @Test void closingFollowedByStationaryPreSubstepStillProvesApproach() {
        assertTrue(NativeApproachEvidence.approaching(PAIR,
                List.of(frame(7,3),frame(7,0)),normal(),7));
        assertFalse(NativeApproachEvidence.approaching(PAIR,
                List.of(frame(7,0),frame(7,0)),normal(),7));
    }
    @Test void wrongTickAndContradictingSubstepsDoNotApprove() {
        assertFalse(NativeApproachEvidence.approaching(PAIR,List.of(frame(6,3)),normal(),7));
        assertFalse(NativeApproachEvidence.approaching(PAIR,List.of(frame(7,3),frame(7,-3)),normal(),7));
        assertFalse(NativeApproachEvidence.approaching(PAIR,List.of(frame(7,3)),
                NativeEffectDirection.unknown("NOT_PROVED"),7));
    }
}
