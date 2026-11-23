package com.physicaldestroydeform.collision;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OptiFrameLifetimeTest {
    @Test void completedPreFrameCannotRetainServerAfterTick(){
        Object server=new Object();
        var stamp=new ContactBatchStamp(server,new Object(),"world",10,500);
        assertFalse(SableFrameCapture.endedFrame(stamp,server,9));
        assertTrue(SableFrameCapture.endedFrame(stamp,server,10));
        assertTrue(SableFrameCapture.endedFrame(stamp,server,11));
    }
    @Test void previousSessionOrUnknownStampMustBeReleased(){
        Object server=new Object();
        var stamp=new ContactBatchStamp(server,new Object(),"world",100,500);
        assertTrue(SableFrameCapture.endedFrame(stamp,new Object(),1));
        assertTrue(SableFrameCapture.endedFrame(ContactBatchStamp.UNKNOWN,server,1));
    }
}
