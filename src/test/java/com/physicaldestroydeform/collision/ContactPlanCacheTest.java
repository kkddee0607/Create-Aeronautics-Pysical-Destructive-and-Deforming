package com.physicaldestroydeform.collision;

import org.junit.jupiter.api.Test;
import net.minecraft.core.BlockPos;
import com.physicaldestroydeform.MaterialProperties;
import com.physicaldestroydeform.deform.MovePlan;
import com.physicaldestroydeform.material.CopycatMaterial;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Cache admission is a convenience only; evictions never change proof semantics. */
class ContactPlanCacheTest {
    @Test void sameWitnessIsAuditedOnlyOnceInReadPlan() {
        var memo=new VerifiedContactGraph.PlanMemo<String>(4);
        var checks=new AtomicInteger();
        assertTrue(memo.read("exact",()->{checks.incrementAndGet();return true;}));
        assertTrue(memo.read("exact",()->{checks.incrementAndGet();return false;}));
        assertEquals(1,checks.get());
        assertEquals(1,memo.size());
    }

    @Test void negativeProofDoesNotBecomePositiveAndNewPlanChecksAgain() {
        var memo=new VerifiedContactGraph.PlanMemo<String>(4);
        assertFalse(memo.read("missing",()->false));
        assertFalse(memo.read("missing",()->true));
        assertTrue(new VerifiedContactGraph.PlanMemo<String>(4).read("missing",()->true));
    }

    @Test void overflowReauditsInsteadOfSuppressingValidContact() {
        var memo=new VerifiedContactGraph.PlanMemo<Integer>(2);
        assertTrue(memo.read(1,()->true));
        assertTrue(memo.read(2,()->true));
        assertFalse(memo.read(3,()->false));
        assertTrue(memo.read(3,()->true));
        assertEquals(2,memo.size());
        assertEquals(0,new VerifiedContactGraph.PlanMemo<Integer>(0).size());
    }

    @Test void invalidationForcesNewMaterialOrShapeProof() {
        var memo=new VerifiedContactGraph.PlanMemo<String>(4);
        assertTrue(memo.read("owner/cell/contact",()->true));
        memo.clear();
        assertEquals(0,memo.size());
        assertFalse(memo.read("owner/cell/contact",()->false));
    }

    @Test void exactPoseNormalAndOwnerGenerationAreNotInterchangeable() {
        var owner=new GameImpactCredit.Owner("WORLD:minecraft:overworld",1);
        var target=new GameImpactCredit.Target(owner.stableKey(),"0,0,0","stone");
        var pos=BlockPos.ZERO;
        var p=new FrameMath.Vec(1,.5,.5);
        var n=new FrameMath.Vec(1,0,0);
        var v=new FrameMath.Vec(0,0,0);
        var scale=new FrameMath.Vec(1,1,1);
        var pose=new FrameMath.Pose(v,v,scale,0,0,0,1);
        var axes=new MovePlan.SignedAxes(-1,0,0,true,10);
        var properties=new MaterialProperties(100,20,30);
        var m=new CopycatMaterial.Resolved(properties,"m",false);
        var memo=new VerifiedContactGraph.PlanMemo<VerifiedContactGraph.Endpoint>(8);
        var exact=new VerifiedContactGraph.Endpoint(owner,target,pos,p,axes,pose,m,n,true,true);
        assertTrue(memo.read(exact,()->true));
        var changedOwner=new GameImpactCredit.Owner(owner.stableKey(),2);
        assertFalse(memo.read(new VerifiedContactGraph.Endpoint(changedOwner,target,pos,p,axes,pose,m,n,true,true),()->false));
        assertFalse(memo.read(new VerifiedContactGraph.Endpoint(owner,target,pos,new FrameMath.Vec(1,.51,.5),axes,pose,m,n,true,true),()->false));
        assertFalse(memo.read(new VerifiedContactGraph.Endpoint(owner,target,pos,p,axes,pose,m,n.times(-1),true,true),()->false));
        assertFalse(memo.read(new VerifiedContactGraph.Endpoint(owner,target,pos,p,axes,pose,new CopycatMaterial.Resolved(properties,"different",false),n,true,true),()->false));
        var moved=new FrameMath.Pose(new FrameMath.Vec(.001,0,0),v,scale,0,0,0,1);
        assertFalse(memo.read(new VerifiedContactGraph.Endpoint(owner,target,pos,p,axes,moved,m,n,true,true),()->false));
    }

    @Test void disabledCacheAlwaysAuditsAndNeverAllocatesAnEntry() {
        var memo=new VerifiedContactGraph.PlanMemo<String>(0);
        assertFalse(memo.read("same",()->false));
        assertTrue(memo.read("same",()->true));
        assertEquals(0,memo.size());
    }

    @Test void failedReadIsNotRemembered() {
        var memo=new VerifiedContactGraph.PlanMemo<String>(4);
        assertThrows(IllegalStateException.class,()->memo.read("same",()->{throw new IllegalStateException();}));
        assertEquals(0,memo.size());
        assertTrue(memo.read("same",()->true));
    }
}
