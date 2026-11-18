package com.physicaldestroydeform.stress;
import org.junit.jupiter.api.Test;
final class StressDurabilityTest {
    @Test void roundTrip()throws Exception{StressDurabilitySelfTest.roundTrip();}
    @Test void duplicateAndChangedCredit()throws Exception{StressDurabilitySelfTest.duplicateAndChangedCredit();}
    @Test void sealedHandoff()throws Exception{StressDurabilitySelfTest.sealedHandoff();}
    @Test void corruption()throws Exception{StressDurabilitySelfTest.corruption();}
    @Test void excessiveRecords()throws Exception{StressDurabilitySelfTest.excessiveRecords();}
    @Test void cancel()throws Exception{StressDurabilitySelfTest.cancel();}
    @Test void clock(){StressDurabilitySelfTest.clock();}
    @Test void origin()throws Exception{StressDurabilitySelfTest.origin();}
    @Test void fairBudget(){StressDurabilitySelfTest.fairBudget();}
    @Test void schedulerCapacity(){StressDurabilitySelfTest.schedulerCapacity();}
    @Test void schedulerFailure(){StressDurabilitySelfTest.schedulerFailure();}
}
