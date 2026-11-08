package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.MaterialProperties;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReverseConePropagationTest {
    private static final String WORLD="WORLD:minecraft:overworld";
    private static GameImpactCredit.Target target(int x,int y,int z) {
        return new GameImpactCredit.Target(WORLD,x+","+y+","+z,"minecraft:stone");
    }
    private static ForcePropagation.Result hit(double force) {
        var face=new ArrayList<GameImpactCredit.Target>();
        for(int x=0;x<5;x++)for(int z=0;z<5;z++)face.add(target(x,0,z));
        return ForcePropagation.plan(WORLD,face,force,(from,x,y,z)->
                y>=0&&y<=28&&x>=0&&x<5&&z>=0&&z<5
                        ?ForcePropagation.Probe.present(target(x,y,z))
                        :ForcePropagation.Probe.empty(),
                ignored->new MaterialProperties(0,255,0,0,false),
                GameImpactCredit.Settings.provisional(),DamageModel.Settings.defaults(),
                new ForcePropagation.Orientation(new FrameMath.Vec(0,1,0),"NATIVE_NORMAL"));
    }
    @Test void allTwentyFiveFaceSeedsStayOnInitialPlaneWithoutInventedLateralSeeds() {
        var result=hit(100_000);
        assertEquals(25,result.shares().stream().filter(s->s.layer()==0).count());
        assertEquals(25,result.shares().stream().filter(s->s.target().localBlock().endsWith(",0,0")
                ||s.target().localBlock().contains(",0,")).count());
        assertTrue(result.shares().stream().allMatch(s->s.target().localBlock().split(",").length==3));
    }
    @Test void strongerImpactReachesDeeperButConservesSingleSourceBudget() {
        var low=hit(100);
        var high=hit(10_000_000);
        assertTrue(high.audit().maxDepth()>low.audit().maxDepth());
        for(var r:List.of(low,high)) {
            assertEquals(r.inputF(),r.assignedF()+r.dampedF()+r.unresolvedF()
                    +r.audit().thresholdDiscardF(),1e-5*r.inputF());
            assertEquals(r.candidateQ(),r.finalQ()+r.dampedQ()+r.audit().thresholdDiscardQ(),1e-5*r.candidateQ()+1e-6);
        }
    }
}
