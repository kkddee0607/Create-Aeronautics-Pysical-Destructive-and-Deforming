package com.physicaldestroydeform.deform;

import com.physicaldestroydeform.MaterialProperties;
import com.physicaldestroydeform.world.WorldCommitCore;
import com.physicaldestroydeform.state.SparseStateKey;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MoveRuntimeOrderTest {
    private static WorldCommitCore.Intent i(String id,int x,int layer){
        var key=new SparseStateKey("minecraft:overworld","WORLD","minecraft:overworld",1,x,64,0,"minecraft:iron_block|default");
        var evidence=new MovePlan.Evidence(new MovePlan.SignedAxes(1,0,0,true,1),500,layer);
        return new WorldCommitCore.Intent(id,key,"0-token",100,new MaterialProperties(112,546,70,35,false),1,10,true,"",evidence);
    }
    @Test void reverseLayerAndKeyOrderIsDeterministic(){
        var a=i("a",0,0);var b=i("b",1,2);var c=i("c",2,2);
        assertEquals(List.of(c,b,a),MoveRuntime.reverseOrder(List.of(a,b,c)));
    }
    @Test void fenceTracksCoordinatesNotFingerprint(){
        var f=new MoveRuntime.Fences();
        var a=i("a",0,0).key();var b=i("b",1,0).key();
        assertTrue(f.acquire("id",a,b));
        var changed=new SparseStateKey(a.dimension(),a.ownerSpace(),a.ownerId(),a.ownerGeneration(),a.x(),a.y(),a.z(),"minecraft:air|default");
        assertTrue(f.held(changed));f.replaced(changed);assertTrue(f.invalidated("id"));
        f.release("id",a,b);assertFalse(f.held(a));assertFalse(f.invalidated("id"));
        f.quarantine(a,b);assertFalse(f.acquire("new",a,b));assertTrue(f.quarantined(b));
    }
}
