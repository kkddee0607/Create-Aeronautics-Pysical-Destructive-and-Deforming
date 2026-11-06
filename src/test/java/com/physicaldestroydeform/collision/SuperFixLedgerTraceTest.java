package com.physicaldestroydeform.collision;

import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.MaterialProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Trace emission does not authorize credits, mutate Q, or live in the stored Grant. */
class SuperFixLedgerTraceTest {
    private static final GameImpactCredit.Pair PAIR=GameImpactCredit.Pair.canonical(
            "test-epoch","minecraft:overworld",new GameImpactCredit.Owner("PLOT:alpha",1),
            new GameImpactCredit.Owner("PLOT:beta",1));
    private static GameImpactCredit.Target at(String owner,int x) {
        return new GameImpactCredit.Target(owner,x+",0,0","minecraft:stone|solid");
    }
    private static GameImpactCredit.Decision approval() {
        var gate=new GameImpactCredit(new GameImpactCredit.Settings(2,2,0.25,64,2,4096,600));
        gate.step(new GameImpactCredit.Frame(PAIR,1,List.of(),true,false,null,null));
        gate.step(new GameImpactCredit.Frame(PAIR,2,List.of(),true,false,null,null));
        var result=gate.step(new GameImpactCredit.Frame(PAIR,3,
                List.of(new GameImpactCredit.ObservedForce(1,2,100,0)),false,true,
                at(PAIR.a().stableKey(),0),at(PAIR.b().stableKey(),0)));
        assertTrue(result.credited());
        return result;
    }
    private static ContactCreditLedger.Grant issue(ContactCreditLedger ledger,
            GameImpactCredit.Decision approved,java.util.function.Consumer<ForcePropagation.Trace> sink) {
        return ledger.issuePropagated(PAIR,approved,ContactCreditLedger.Source.SABLE_POST_EFFECT,
                List.of(new ContactCreditLedger.Region(at(PAIR.a().stableKey(),0),
                        at(PAIR.b().stableKey(),0))),
                (from,x,y,z)->x==1&&y==0&&z==0
                        ?ForcePropagation.Probe.present(at(from.ownerKey(),1))
                        :ForcePropagation.Probe.empty(),
                fingerprint->new MaterialProperties(0,100,0,0,false),
                GameImpactCredit.Settings.provisional(),DamageModel.Settings.defaults(),
                Map.of(PAIR.a().stableKey(),new ForcePropagation.Orientation(
                            new FrameMath.Vec(1,0,0),"VERIFIED"),
                       PAIR.b().stableKey(),new ForcePropagation.Orientation(
                            new FrameMath.Vec(1,0,0),"VERIFIED")),sink);
    }
    @Test void onlyAcceptedGrantEmitsAndDuplicateDoesNotEmit() {
        var ledger=new ContactCreditLedger();
        var trace=new ArrayList<ForcePropagation.Trace>();
        var approved=approval();
        var grant=issue(ledger,approved,trace::add);
        assertTrue(grant.credited());
        assertEquals(2,trace.stream().filter(t->t instanceof ForcePropagation.Edge).count());
        assertEquals(2,trace.stream().filter(t->t instanceof ForcePropagation.Receipt).count());
        assertEquals(grant.issuedQ(),grant.accounted(),1e-8);
        int prior=trace.size();
        var duplicate=issue(ledger,approved,trace::add);
        assertEquals(ContactCreditLedger.Status.DUPLICATE_CAUSE,duplicate.status());
        assertEquals(prior,trace.size());
        assertEquals(1,ledger.size());
    }
    @Test void badNeighborCannotPublishTraceOrSpendCredit() {
        var ledger=new ContactCreditLedger();
        var trace=new ArrayList<ForcePropagation.Trace>();
        var bad=ledger.issuePropagated(PAIR,approval(),ContactCreditLedger.Source.SABLE_POST_EFFECT,
                List.of(new ContactCreditLedger.Region(at(PAIR.a().stableKey(),0),
                        at(PAIR.b().stableKey(),0))),
                (from,x,y,z)->x==1&&y==0&&z==0
                        ?ForcePropagation.Probe.present(at("WORLD:WRONG_OWNER",1))
                        :ForcePropagation.Probe.empty(),
                fp->new MaterialProperties(0,100,0,0,false),
                GameImpactCredit.Settings.provisional(),DamageModel.Settings.defaults(),
                Map.of(PAIR.a().stableKey(),new ForcePropagation.Orientation(
                            new FrameMath.Vec(1,0,0),"VERIFIED"),
                       PAIR.b().stableKey(),new ForcePropagation.Orientation(
                            new FrameMath.Vec(1,0,0),"VERIFIED")),trace::add);
        assertEquals(ContactCreditLedger.Status.INVALID_TARGET_SET,bad.status());
        assertTrue(trace.isEmpty());
        assertEquals(0,ledger.size());
    }
    @Test void throwingLogSinkCannotRevokeAcceptedCredit() {
        var ledger=new ContactCreditLedger();
        var grant=issue(ledger,approval(),event->{throw new IllegalStateException("logger unavailable");});
        assertTrue(grant.credited());
        assertEquals(1,ledger.size());
    }
}
