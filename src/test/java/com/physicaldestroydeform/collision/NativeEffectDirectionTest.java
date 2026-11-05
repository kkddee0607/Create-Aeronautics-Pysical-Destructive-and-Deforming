package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.diagnostics.NativeCollisionTelemetry;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class NativeEffectDirectionTest {
    private static final String DIM="minecraft:overworld";
    private static final UUID ID=UUID.fromString("a9667991-83c3-4a18-b6e6-86844635111b");
    private static final String PLOT="PLOT:"+ID;
    private static final String WORLD="WORLD:"+DIM;
    private static final GameImpactCredit.Pair PAIR=GameImpactCredit.Pair.canonical("run",DIM,
            new GameImpactCredit.Owner(WORLD,1),new GameImpactCredit.Owner(PLOT,1));
    private static NativeCollisionTelemetry.OwnerFrame frame(double qz,double qw) {
        return new NativeCollisionTelemetry.OwnerFrame(ID,
                new FrameMath.Pose(new FrameMath.Vec(0,0,0),new FrameMath.Vec(0,0,0),
                    new FrameMath.Vec(1,1,1),0,0,qz,qw));
    }
    private static NativeCollisionTelemetry.Contact contact(int plotId,
                        double ax,double ay,double az,double bx,double by,double bz,
                        NativeCollisionTelemetry.OwnerFrame frame) {
        return new NativeCollisionTelemetry.Contact(-1,plotId,100,ax,ay,az,bx,by,bz,
                0,0,0,0,0,0,DIM,10,null,frame);
    }
    @Test void worldFixedNormalAndPlotLocalNormalYieldOpposingInwardVectors() {
        var witness=NativeEffectDirection.verify(PAIR,List.of(contact(7,0,1,0,0,-1,0,frame(0,1))));
        assertEquals(2,witness.owners().size());
        assertEquals(1,witness.corroboratedRecords());
        assertEquals(-1,witness.forOwner(WORLD).inward().y(),1e-9);
        assertEquals(1,witness.forOwner(PLOT).inward().y(),1e-9);
    }
    @Test void rotatedPlotNativeLocalNormalsAreTransformedForOppositionProof() {
        double r=Math.sqrt(.5);
        // Rotated +X plot-local normal becomes +Y world normal.
        var witness=NativeEffectDirection.verify(PAIR,List.of(contact(7,0,-1,0,1,0,0,frame(r,r))));
        assertEquals(2,witness.owners().size());
        assertEquals(-1,witness.forOwner(PLOT).inward().x(),1e-8);
    }
    @Test void mismatchedWorldAndPlotNormalsOrOwnerAreUnknown() {
        assertTrue(NativeEffectDirection.verify(PAIR,List.of(contact(7,0,1,0,0,1,0,frame(0,1)))).owners().isEmpty());
        assertTrue(NativeEffectDirection.verify(PAIR,List.of(contact(7,0,1,0,0,-1,0,null))).owners().isEmpty());
    }
    @Test void gridAxisProofIsSeparateFromSubstepAndAllowsWorldPlot() {
        var contact=contact(7,0,1,0,0,-1,0,frame(0,1));
        var pose=frame(0,1).pose();
        var pre=new CollisionFrame.Snapshot(41,10,DIM,CollisionFrame.Phase.PRE_SOLVER,
                List.of(new CollisionFrame.Body(ID,0,15,0,15,pose,
                        new FrameMath.Vec(0,0,0),new FrameMath.Vec(0,0,0),
                        new FrameMath.Vec(0,0,0),true,null,7)),true);
        var verified=NativeEffectDirection.verify(PAIR,List.of(contact),List.of(pre));
        assertEquals(2,verified.owners().size());
        var axes=NativeEffectDirection.gridAxes(verified,10,41);
        assertEquals(-1,axes.get(WORLD).signedY());
        assertEquals(1,axes.get(PLOT).signedY());
        assertEquals(-1,axes.get(PLOT).sourceSubstep());
    }
    @Test void rotatingPoseAcrossPreSolverSubstepsMustRejectStaleEffectFrame() {
        var contact=contact(7,0,1,0,0,-1,0,frame(0,1));
        double r=Math.sqrt(.5);
        var rotated=frame(r,r).pose();
        var pre=new CollisionFrame.Snapshot(41,10,DIM,CollisionFrame.Phase.PRE_SOLVER,
                List.of(new CollisionFrame.Body(ID,0,15,0,15,rotated,
                        new FrameMath.Vec(0,0,0),new FrameMath.Vec(0,0,0),
                        new FrameMath.Vec(0,0,0),true,null,7)),true);
        var result=NativeEffectDirection.verify(PAIR,List.of(contact),List.of(pre));
        assertTrue(result.owners().isEmpty());
        assertEquals("PLOT_NORMAL_FRAME_ROTATION_UNVERIFIED",result.reason());
    }
    @Test void obliqueManifoldKeepsVectorButDoesNotInventGridAxis() {
        double n=Math.sqrt(.5);
        var verified=NativeEffectDirection.verify(PAIR,List.of(
                contact(7,n,n,0,-n,-n,0,frame(0,1))));
        assertEquals(2,verified.owners().size());
        assertEquals(0,NativeEffectDirection.gridAxes(verified,10,41).size());
    }
    @Test void disagreementAcrossNativeRecordsRejectsDirection() {
        var witness=NativeEffectDirection.verify(PAIR,List.of(contact(7,0,1,0,0,-1,0,frame(0,1)),
                contact(7,1,0,0,-1,0,0,frame(0,1))));
        assertTrue(witness.owners().isEmpty());
    }
}
