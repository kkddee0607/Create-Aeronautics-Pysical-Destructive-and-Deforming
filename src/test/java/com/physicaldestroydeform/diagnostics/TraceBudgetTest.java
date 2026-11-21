package com.physicaldestroydeform.diagnostics;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraceBudgetTest {
    @Test void samplesDoNotExpandOrChangeInput(){
        var input=List.of(1,2,3,4,5);
        assertEquals(List.of(1,2),TraceBudget.sample(input,2));
        assertEquals(input,TraceBudget.sample(input,16));
        assertTrue(TraceBudget.sample(input,0).isEmpty());
        assertEquals(5,input.size());
    }
}
