package com.physicaldestroydeform;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BlockDamageStateFixedTest {
    @Test void residueRoundtripsWithoutDamageCeil() {
        BlockDamageState state=new BlockDamageState();state.addRawDamage(.1);
        assertEquals(0,state.damage());assertEquals(25,state.remainder());
        BlockDamageState copy=state.copy();assertEquals(25,copy.remainder());
        state.addRawDamage(.1);assertEquals(50,state.remainder());
        assertEquals(25,copy.remainder());
    }
    @Test void damageSaturatesAtMaxFixed() {
        BlockDamageState s=new BlockDamageState();s.addFixedDamage(Long.MAX_VALUE);
        assertEquals(1024,s.damage());assertEquals(0,s.remainder());
        s.addFixedDamage(999);assertEquals(1024L*256,s.fixedDamage());
    }
    @Test void noPristineSerializationWhenRemainderIsPresent() {
        BlockDamageState s=new BlockDamageState();assertTrue(s.isPristine());
        s.setRemainder(1);assertFalse(s.isPristine());
    }
    @Test void invalidRemainderAndNegativesRejected() {
        BlockDamageState s=new BlockDamageState();
        assertThrows(IllegalArgumentException.class,()->s.setRemainder(256));
        assertThrows(IllegalArgumentException.class,()->s.addFixedDamage(-1));
        assertThrows(IllegalArgumentException.class,()->s.addRawDamage(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,()->BlockDamageState.unpack24(-1));
    }
}
