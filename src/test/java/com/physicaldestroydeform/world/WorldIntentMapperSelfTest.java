package com.physicaldestroydeform.world;

import com.physicaldestroydeform.MaterialProperties;
import com.physicaldestroydeform.collision.ContactCreditLedger;
import com.physicaldestroydeform.collision.GameImpactCredit;
import java.util.List;

/** Sealed Grant -> World/Plot scope isolation and stable ApplyId identity. */
public final class WorldIntentMapperSelfTest {
    private static int checks;
    private static void check(boolean ok,String what){checks++;if(!ok)throw new AssertionError(what);}
    public static void main(String[] args) {
        var a=new GameImpactCredit.Owner("WORLD:minecraft:overworld",1);
        var b=new GameImpactCredit.Owner("PLOT:123e4567-e89b-12d3-a456-426614174000",2);
        var pair=GameImpactCredit.Pair.canonical("run-1","minecraft:overworld",a,b);
        var id=new ContactCreditLedger.CreditId(pair,7);
        var world=new GameImpactCredit.Target(a.stableKey(),"9,65,9","minecraft:stone|Block{minecraft:stone}");
        var plot=new GameImpactCredit.Target(b.stableKey(),"7,4,3","minecraft:stone|Block{minecraft:stone}");
        var shareW=new ContactCreditLedger.Share(world,4.0);
        var shareP=new ContactCreditLedger.Share(plot,2.0);
        var grant=new ContactCreditLedger.Grant(id,ContactCreditLedger.Source.SABLE_POST_EFFECT,
                ContactCreditLedger.Status.ISSUED,123,8.0,List.of(shareW),List.of(shareP),2.0,1,"test");
        MaterialProperties stone=new MaterialProperties(59,91,10,9,false);
        var mapped=WorldIntentMapper.project(grant,14,f->stone,k->"incarnation-1");
        check(mapped.world().size()==1,"World only");
        check(mapped.world().getFirst().sealedQ()==4,"no Q recalculation");
        check(mapped.world().getFirst().materialGeneration()==14,"snapshot generation");
        check(mapped.plotQ()==2,"Plot reserved but not mutated");
        check(mapped.missingMaterialQ()==0&&mapped.missingIncarnationQ()==0,"full lookup");
        var mappedAgain=WorldIntentMapper.project(grant,14,f->stone,k->"incarnation-1");
        check(mapped.world().getFirst().applyId().equals(mappedAgain.world().getFirst().applyId()),"deterministic ID");
        var noTag=WorldIntentMapper.project(grant,14,f->stone,k->null);
        check(noTag.world().isEmpty()&&noTag.missingIncarnationQ()==4,"unverified token rejects World");
        var noMat=WorldIntentMapper.project(grant,14,f->null,k->"incarnation-1");
        check(noMat.world().isEmpty()&&noMat.missingMaterialQ()==4,"no manufactured material");
        var later=new ContactCreditLedger.CreditId(pair,8);
        var event2=new ContactCreditLedger.Grant(later,grant.source(),grant.status(),124,
                grant.issuedQ(),grant.a(),grant.b(),grant.unused(),1,"test");
        check(!WorldIntentMapper.id(grant,mapped.world().getFirst().key()).equals(
                WorldIntentMapper.id(event2,mapped.world().getFirst().key())),"episode changes ApplyId");
        check(mapped.world().getFirst().key().ownerSpace().equals("WORLD"),"key is World-local");
        System.out.println("WORLD_INTENT_MAPPER_SELFTEST PASS checks="+checks);
    }
}
