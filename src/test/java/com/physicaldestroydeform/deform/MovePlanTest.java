package com.physicaldestroydeform.deform;

import com.physicaldestroydeform.MaterialProperties;
import com.physicaldestroydeform.state.SparseStateKey;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MovePlanTest {
    private static final SparseStateKey KEY=new SparseStateKey("minecraft:overworld","PLOT",
            "123e4567-e89b-12d3-a456-426614174000",2,1,2,3,"example:block|state");
    @Test void thresholdAndElasticBreakFirstBeforeCandidate(){
        var ductile=new MaterialProperties(80,100,100,0,false);
        assertEquals(0.25,MovePlan.yieldMultiplier(100),1e-10);
        assertEquals(1.0,MovePlan.yieldMultiplier(0),1e-10);
        var axis=new MovePlan.SignedAxes(1,-1,0,true,7);
        var candidate=MovePlan.plan("credit",KEY,ductile,40*256L,20*256L,800,8,axis);
        assertEquals(MovePlan.Status.CANDIDATE_ONLY,candidate.status());
        assertEquals(List.of(new MovePlan.Step(1,0,0),new MovePlan.Step(0,-1,0)),candidate.steps());
        assertFalse(candidate.applied());
        assertEquals(8,candidate.blockedQ(f->f*0.1),1e-10);
        var breakFirst=MovePlan.plan("credit",KEY,ductile,99*256L,2*256L,800,8,axis);
        assertEquals(MovePlan.Status.BREAK_FIRST,breakFirst.status());
        assertTrue(breakFirst.steps().isEmpty());
        assertEquals(0.0,breakFirst.blockedQ(f->f*0.1),1e-10);
        var unknown=MovePlan.plan("credit",KEY,ductile,40*256L,20*256L,800,8,null);
        assertEquals(MovePlan.Status.DIRECTION_UNKNOWN,unknown.status());
        assertFalse(unknown.applied());
    }
    @Test void atomicOwnerLocalProofIsNotAWorldMutation(){
        var material=new MaterialProperties(80,100,100,0,false);
        var direction=new MovePlan.SignedAxes(1,0,-1,true,9);
        var plan=MovePlan.plan("atomic",KEY,material,40*256L,20*256L,300,3,direction);
        byte[] be={1,2,3};
        var proof=plan.atomicContract(direction,2,"minecraft:air|default",
                "minecraft:stone|default",be,40,1,1,"PDD-IDS-1.0.8",
                "ROLLBACK_OR_QUARANTINE_REQUIRED");
        assertEquals(KEY.ownerId(),proof.after().ownerId());
        assertEquals(KEY.x()+1,proof.after().x());
        assertEquals(KEY.z()-1,proof.after().z());
        assertFalse(proof.committed());
        be[0]=9;
        assertEquals(1,proof.bePayload()[0]);
        var copy=proof.bePayload();copy[0]=9;
        assertEquals(1,proof.bePayload()[0]);
        assertThrows(IllegalStateException.class,()->plan.atomicContract(
                new MovePlan.SignedAxes(1,0,-1,true,10),2,"minecraft:air|default",
                "minecraft:stone|default",null,40,0,1,"PDD", "ROLLBACK"));
        assertThrows(IllegalStateException.class,()->plan.atomicContract(
                new MovePlan.SignedAxes(0,1,0,true,9),2,"minecraft:air|default",
                "minecraft:stone|default",null,40,0,1,"PDD", "ROLLBACK"));
    }
    @Test void belowYieldCannotInventDirectionOrMove(){
        var hard=new MaterialProperties(100,100,0,0,false);
        var verified=new MovePlan.SignedAxes(0,0,-1,true,3);
        var result=MovePlan.plan("credit",KEY,hard,0,30*256L,100,5,verified);
        assertEquals(MovePlan.Status.BELOW_YIELD,result.status());
        assertEquals(0.0,result.blockedQ(f->f*0.2),1e-10);
        assertThrows(IllegalArgumentException.class,()->new MovePlan.SignedAxes(1,1,1,true,1));
        assertThrows(IllegalArgumentException.class,()->new MovePlan.Step(1,1,0));
        assertThrows(IllegalArgumentException.class,()->new MovePlan.Step(Integer.MIN_VALUE,0,0));
        assertThrows(IllegalArgumentException.class,()->new MovePlan.SignedAxes(
                Integer.MIN_VALUE,0,0,true,1));
    }
}
