package com.physicaldestroydeform.plot;

import com.physicaldestroydeform.state.SparseStateKey;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlotIdentityTest {
    private static final String DIM="minecraft:the_nether";
    private static final String ID="123e4567-e89b-12d3-a456-426614174000";
    private static SparseStateKey key(String id,long generation){
        return new SparseStateKey(DIM,"PLOT",id,generation,-32,80,15,"example:block|state");
    }
    @Test void plotNamespaceIsReversibleAndOwnerIsolated(){
        var a=key(ID,3);
        var b=key("123e4567-e89b-12d3-a456-426614174001",3);
        var c=key(ID,4);
        var virtual=PlotKeyCodec.storage(a);
        assertEquals("WORLD",virtual.ownerSpace());
        assertEquals(virtual.dimension(),virtual.ownerId());
        assertEquals(a,PlotKeyCodec.real(virtual));
        assertNotEquals(virtual.dimension(),PlotKeyCodec.storage(b).dimension());
        assertNotEquals(virtual.dimension(),PlotKeyCodec.storage(c).dimension());
        assertThrows(IllegalArgumentException.class,()->PlotKeyCodec.storage(virtual));
        assertThrows(IllegalArgumentException.class,()->PlotKeyCodec.real(a));
    }
    @Test void epochAndSplitQuarantineNeverFollowCoordinateAlone(){
        var fence=new PlotOwnerFence();
        var owner=PlotOwnerFence.Owner.of(key(ID,3));
        Object physical=new Object();
        var stamp=new PlotOwnerFence.Stamp(11,128,64,256,physical);
        assertTrue(fence.bind(owner,stamp,false));
        assertTrue(fence.verify(owner,stamp,false));
        assertFalse(fence.bind(owner,new PlotOwnerFence.Stamp(11,128,64,256,new Object()),false));
        assertTrue(fence.orphaned(owner));
        assertFalse(fence.bind(owner,stamp,false));
        var different=new PlotOwnerFence.Owner(DIM,UUID.fromString("123e4567-e89b-12d3-a456-426614174002"),3);
        assertTrue(fence.bind(different,new PlotOwnerFence.Stamp(12,128,64,256,new Object()),false));
        fence.orphanAncestors(DIM,different.uuid());
        assertTrue(fence.orphaned(different));
        assertFalse(fence.verify(different,stamp,false));
    }
    @Test void splitChildrenNeverInheritDamageByUuid(){
        var fence=new PlotOwnerFence();
        var child=new PlotOwnerFence.Owner(DIM,UUID.randomUUID(),1);
        var stamp=new PlotOwnerFence.Stamp(7,16,64,16,new Object());
        assertFalse(fence.bind(child,stamp,true));
        assertTrue(fence.orphaned(child));
    }
}
