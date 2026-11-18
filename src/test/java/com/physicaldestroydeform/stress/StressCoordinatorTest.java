package com.physicaldestroydeform.stress;
import org.junit.jupiter.api.Test;
final class StressCoordinatorTest {
    @Test void normalHandoff()throws Exception{StressCoordinatorSelfTest.normalHandoff();}
    @Test void replayAfterPartialHandoff()throws Exception{StressCoordinatorSelfTest.replayAfterPartialHandoff();}
    @Test void unsealedRestart()throws Exception{StressCoordinatorSelfTest.unsealedRestart();}
    @Test void validationVeto()throws Exception{StressCoordinatorSelfTest.validationVeto();}
    @Test void backpressure()throws Exception{StressCoordinatorSelfTest.backpressure();}
    @Test void failedSink()throws Exception{StressCoordinatorSelfTest.failedSink();}
    @Test void duplicateTick()throws Exception{StressCoordinatorSelfTest.duplicateTick();}
}
