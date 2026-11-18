package com.physicaldestroydeform.stress;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.physicaldestroydeform.stress.StressPropagation.*;
import static com.physicaldestroydeform.stress.StressRuntimeProtocolTest.*;

/** Source tests only: the user executes JUnit. No Minecraft startup needed. */
class StressRuntimeBudgetTest {
    private static final class Yield extends RuntimeException {}
    @Test void oneWorkUnitPerTickEventuallyExpandsSixFacesWithoutBudgetRenewal(){
        var policy=Policy.defaults();
        var session=new Session(List.of(new Seed(node(0),100,null)),policy,SETTINGS,100,true);
        var cursor=new StressProbeCursor();
        var unlimited=new Session(List.of(new Seed(node(0),100,null)),policy,SETTINGS,100,true);
        unlimited.advance(line(2),100,512);
        int[] used={0},totalReads={0};
        Runnable charge=()->{if(used[0]>=1)throw new Yield();used[0]++;};
        Graph frozen=cell->{charge.run();totalReads[0]++;return line(2).read(cell);};
        for(long tick=100;tick<200&&!session.ledger().finished();tick++){
            used[0]=0;
            try{cursor.advance(session,frozen,tick,charge);}catch(Yield exhausted){/* retain cursor */}
            assertTrue(used[0]<=1);
            assertEquals(100,session.ledger().accountedQ(),1e-10);
        }
        assertTrue(session.ledger().finished());
        assertEquals(unlimited.plans(),session.plans());
        assertEquals(unlimited.ledger(),session.ledger());
        assertEquals(21,totalReads[0]); // one root + two nodes, seven graph reads apiece
    }
    @Test void aYieldedProbeCannotChangeItsRequestedCell(){
        var cursor=new StressProbeCursor();
        var first=new Session(List.of(new Seed(node(0),100,null)),Policy.defaults(),SETTINGS,100,true);
        int[] reads={0};
        assertThrows(Yield.class,()->cursor.advance(first,cell->{if(reads[0]++==1)throw new Yield();return line(2).read(cell);},100,()->{}));
        var different=new Session(List.of(new Seed(node(1),100,null)),Policy.defaults(),SETTINGS,100,true);
        assertThrows(IllegalStateException.class,()->cursor.advance(different,line(2),101,()->{}));
    }
}
