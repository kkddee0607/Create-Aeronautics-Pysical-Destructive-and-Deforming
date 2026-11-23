package com.physicaldestroydeform.material;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CopycatTraceHistoryTest {
    private static final TargetMaterials.TraceKey CELL=new TargetMaterials.TraceKey("minecraft:overworld","WORLD:minecraft:overworld","1,2,3");
    private static final TargetMaterials.TraceValue STATE=new TargetMaterials.TraceValue(1,"copycats:copycat_slope|Block",new CopycatMaterial.Capture(null,"BE_MISSING","",0));

    @Test void unchangedStateIsDeduplicatedUnlessExplicitFullTrace(){
        var history=new TargetMaterials.TraceHistory(2);
        assertEquals(TargetMaterials.TraceDecision.EMIT,history.decide(CELL,STATE,false,true));
        assertEquals(TargetMaterials.TraceDecision.DUPLICATE,history.decide(CELL,STATE,false,true));
        assertEquals(TargetMaterials.TraceDecision.EMIT,history.decide(CELL,STATE,true,true));
    }

    @Test void changedRejectionAndGenerationEachEmit(){
        var history=new TargetMaterials.TraceHistory(2);
        history.decide(CELL,STATE,false,true);
        var changed=new TargetMaterials.TraceValue(1,STATE.fingerprint(),new CopycatMaterial.Capture(null,"STATE_MISMATCH","",0));
        assertEquals(TargetMaterials.TraceDecision.EMIT,history.decide(CELL,changed,false,true));
        assertEquals(TargetMaterials.TraceDecision.EMIT,history.decide(CELL,new TargetMaterials.TraceValue(2,changed.fingerprint(),changed.capture()),false,true));
    }

    @Test void rateLimitedChangeIsNotMarkedLogged(){
        var history=new TargetMaterials.TraceHistory(2);
        assertEquals(TargetMaterials.TraceDecision.CAP,history.decide(CELL,STATE,false,false));
        assertEquals(TargetMaterials.TraceDecision.EMIT,history.decide(CELL,STATE,false,true));
    }

    @Test void boundedHistoryAndResetPermitFreshLogging(){
        var history=new TargetMaterials.TraceHistory(2);
        history.decide(CELL,STATE,false,true);
        history.decide(new TargetMaterials.TraceKey(CELL.dimension(),CELL.owner(),"4,5,6"),STATE,false,true);
        history.decide(new TargetMaterials.TraceKey(CELL.dimension(),CELL.owner(),"7,8,9"),STATE,false,true);
        assertEquals(2,history.size());
        assertEquals(TargetMaterials.TraceDecision.EMIT,history.decide(CELL,STATE,false,true));
        history.clear();assertEquals(0,history.size());assertEquals(TargetMaterials.TraceDecision.EMIT,history.decide(CELL,STATE,false,true));
    }
}
