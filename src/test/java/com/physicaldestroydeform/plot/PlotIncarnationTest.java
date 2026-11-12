package com.physicaldestroydeform.plot;

import com.physicaldestroydeform.state.SparseStateKey;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlotIncarnationTest {
    @Test void centerOwnerEpochAndReplacementRevisionCannotReuseOldDamage(){
        var k=new SparseStateKey("minecraft:overworld","PLOT",
                "123e4567-e89b-12d3-a456-426614174000",1,3,2,4,"minecraft:stone|default");
        String first=PlotIncarnation.token(k,100,64,200,0);
        assertEquals(first,PlotIncarnation.token(k,100,64,200,0));
        assertNotEquals(first,PlotIncarnation.token(k,116,64,200,0));
        assertNotEquals(first,PlotIncarnation.token(k,100,64,200,1));
        assertNotEquals(first,PlotIncarnation.token(new SparseStateKey(k.dimension(),"PLOT",k.ownerId(),2,
                k.x(),k.y(),k.z(),k.fingerprint()),100,64,200,0));
        assertNotEquals(first,PlotIncarnation.token(new SparseStateKey(k.dimension(),"PLOT",k.ownerId(),1,
                k.x(),k.y(),k.z(),"minecraft:glass|default"),100,64,200,0));
    }
}
