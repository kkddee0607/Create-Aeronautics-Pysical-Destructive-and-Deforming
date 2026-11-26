package com.physicaldestroydeform.stress;

import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RogFixResidualWaitTest {
    @Test void noResidualVisitsBeforeOriginalDeadline(){
        var wait=new StressResidualWait();var ready=new ArrayDeque<String>();
        wait.park("one",1300);wait.park("two",1301);
        for(int t=101;t<1300;t++)wait.wake(t,false,ready);
        assertTrue(ready.isEmpty());assertEquals(2,wait.size());
        wait.wake(1300,false,ready);assertEquals(List.of("one"),List.copyOf(ready));
        assertEquals(1,wait.size());wait.wake(1301,false,ready);assertEquals(List.of("one","two"),List.copyOf(ready));
        wait.wake(1302,false,ready);assertEquals(2,ready.size());assertEquals(0,wait.size());
    }
    @Test void generationChangeWakesAllResidualsWithoutReissue(){
        var wait=new StressResidualWait();var ready=new ArrayDeque<String>();
        wait.park("later",1301);wait.park("early",1300);wait.park("same",1300);
        wait.wake(200,true,ready);assertEquals(List.of("early","same","later"),List.copyOf(ready));
        assertEquals(0,wait.size());wait.wake(1301,false,ready);assertEquals(3,ready.size());
    }
    @Test void dependencyInvalidationWakesExactlyOnce(){
        var wait=new StressResidualWait();var ready=new ArrayDeque<String>();
        wait.park("dependency",250);assertTrue(wait.wakeOne("dependency",ready));
        assertFalse(wait.wakeOne("dependency",ready));wait.wake(250,false,ready);
        assertEquals(List.of("dependency"),List.copyOf(ready));assertEquals(0,wait.size());
    }
    @Test void stopDropsOnlyInMemoryWakeIndex(){
        var wait=new StressResidualWait();var ready=new ArrayDeque<String>();
        wait.park("durable",1300);wait.clear();wait.wake(1300,false,ready);
        assertEquals(0,wait.size());assertTrue(ready.isEmpty());
    }
}
