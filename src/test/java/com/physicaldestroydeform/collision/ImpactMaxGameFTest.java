package com.physicaldestroydeform.collision;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ImpactMaxGameFTest {
    @Test void millionBlocksOfFourMassAtTenMetersPerSecondSaturatesWithoutRejecting() {
        var s=GameImpactCredit.Settings.provisional();
        assertEquals(40_000_000d,GameImpactCredit.MAX_GAME_F,0);
        assertTrue(s.candidate(GameImpactCredit.MAX_GAME_F)>65_536);
        assertEquals(s.candidate(GameImpactCredit.MAX_GAME_F),s.candidate(1e12),1e-6);
        var pair=GameImpactCredit.Pair.canonical("run","minecraft:overworld",
                new GameImpactCredit.Owner("PLOT:alpha",1),
                new GameImpactCredit.Owner("WORLD:minecraft:overworld",1));
        var one=new GameImpactCredit.Target(pair.a().stableKey(),"0,0,0","minecraft:stone");
        var two=new GameImpactCredit.Target(pair.b().stableKey(),"0,64,0","minecraft:stone");
        var credit=new GameImpactCredit(s);
        credit.step(new GameImpactCredit.Frame(pair,1,List.of(),true,false,null,null));
        credit.step(new GameImpactCredit.Frame(pair,2,List.of(),true,false,null,null));
        var huge=new GameImpactCredit.ObservedForce(1,-1,1e12,0);
        var accepted=credit.step(new GameImpactCredit.Frame(pair,3,List.of(huge),false,true,one,two));
        assertEquals(GameImpactCredit.Reason.APPROVED,accepted.reason());
        assertEquals(40_000_000d,accepted.peakForce(),0);
        assertEquals(s.candidate(40_000_000d),accepted.issuedQ(),1e-6);
        assertEquals(accepted.issuedQ(),accepted.allocation().total(),1e-6);
        assertEquals(GameImpactCredit.Reason.DUPLICATE_EPISODE,
                credit.step(new GameImpactCredit.Frame(pair,4,List.of(huge),false,true,one,two)).reason());
    }
}
