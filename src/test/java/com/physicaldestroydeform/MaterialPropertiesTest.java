package com.physicaldestroydeform;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MaterialPropertiesTest {
    @Test void bounds() {
        assertDoesNotThrow(()->new MaterialProperties(0,0,0));
        assertDoesNotThrow(()->new MaterialProperties(255,100,100));
        assertThrows(IllegalArgumentException.class,()->new MaterialProperties(256,0,0));
        assertThrows(IllegalArgumentException.class,()->new MaterialProperties(0,101,0));
        assertThrows(IllegalArgumentException.class,()->new MaterialProperties(50,0,10,20,false));
        assertThrows(IllegalArgumentException.class,()->new MaterialProperties(50,20,10,20,false,Double.NaN));
    }
    @Test void percentages() {
        assertEquals(.75,new MaterialProperties(0,75,25).ductility());
        assertEquals(.25,new MaterialProperties(0,75,25).elasticity());
    }
    @Test void independentThresholdAndYield() {
        var a=new MaterialProperties(100,40,70,35,false);
        var b=new MaterialProperties(100,200,70,35,false);
        assertEquals(a.hardness(),b.hardness());
        assertNotEquals(a.threshold(),b.threshold());
        assertEquals(70,a.ductilityPercent());
        assertEquals(MaterialProperties.defaultYield(70),a.yieldThreshold());
        assertEquals(1,MaterialProperties.defaultThreshold(0));
        assertEquals(1024,MaterialProperties.defaultThreshold(255));
        assertDoesNotThrow(()->new MaterialProperties(100,1024,25,40,false));
        assertThrows(IllegalArgumentException.class,()->new MaterialProperties(100,1025,25,40,false));
    }
}
