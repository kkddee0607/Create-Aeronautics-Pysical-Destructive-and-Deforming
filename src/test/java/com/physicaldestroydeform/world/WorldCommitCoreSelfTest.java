package com.physicaldestroydeform.world;

import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.MaterialProperties;
import com.physicaldestroydeform.state.SparseStateKey;
import java.nio.file.*;
import java.util.*;

/** Runnable in Java 21 without a Minecraft runtime; not a substitute for NeoForge/JUnit/in-game tests. */
public final class WorldCommitCoreSelfTest {
    private static final String DIM="minecraft:overworld";
    private static final MaterialProperties STONE=new MaterialProperties(59,91,10,9,false);
    private static int checks;
    private static void check(boolean yes,String error){checks++;if(!yes)throw new AssertionError(error);}
    private static SparseStateKey key(int x) {return new SparseStateKey(DIM,"WORLD",DIM,1,x,64,0,
            "minecraft:stone|Block{minecraft:stone}");}
    private static WorldCommitCore.Intent hit(String id,SparseStateKey key,double q) {
        return new WorldCommitCore.Intent(id,key,"incarnation-1",q,STONE,1,100,true);
    }
    private static final class Fake implements WorldCommitCore.WorldGateway {
        boolean loaded=true,air=false,be=false,multiblock=false,immune=false,stepping=false,deny=false,cancel=false,thread=true;
        boolean flipAfterCanBreak=false,breakReportsFalse=false;
        String incarnation="incarnation-1";
        int drops,breaks;
        @Override public boolean isServerThread(){return thread;}
        @Override public WorldCommitCore.Observation readIfLoaded(SparseStateKey key) {
            if(!loaded)return null;
            return new WorldCommitCore.Observation(DIM,key.fingerprint(),1,incarnation,true,air,be&&!air,multiblock&&!air,immune&&!air,stepping);
        }
        @Override public WorldCommitCore.Permission canDamage(SparseStateKey key,WorldCommitCore.Observation expected) {
            return deny?WorldCommitCore.Permission.DENY:WorldCommitCore.Permission.ALLOW;
        }
        @Override public WorldCommitCore.Permission canBreak(SparseStateKey key,WorldCommitCore.Observation expected) {
            if(flipAfterCanBreak)incarnation="incarnation-changed-during-permission";
            return deny||cancel?WorldCommitCore.Permission.DENY:WorldCommitCore.Permission.ALLOW;
        }
        @Override public boolean breakVanillaOnce(SparseStateKey key) {
            breaks++;if(air)return false;
            air=true;drops++;return !breakReportsFalse;
        }
    }
    public static void main(String[] args)throws Exception{
        Path dir=Files.createTempDirectory("pdd-world-core-test");
        // Distinct impacts: the first accepted damage must be used by the second plan.
        Path file=dir.resolve("world.dat");
        var journal=new WorldReceiptJournal(file);
        var core=new WorldCommitCore(journal,DamageModel.Settings.defaults(),1);
        var fake=new Fake();SparseStateKey pos=key(2);
        core.submit(hit("impact-1",pos,0.5));
        var first=core.tick(fake,101).get(0);
        check(first.result()==WorldCommitCore.Outcome.APPLIED_DAMAGE,"initial damage");
        long before=journal.get(pos).damage()*256L+journal.get(pos).remainder();
        check(before>0,"fixed remainder");
        core.submit(hit("impact-2",pos,0.5));
        check(core.tick(fake,102).get(0).result()==WorldCommitCore.Outcome.APPLIED_DAMAGE,"second impact");
        long after=journal.get(pos).damage()*256L+journal.get(pos).remainder();
        check(after>before,"independent Q adds damage");
        check(core.submit(hit("impact-1",pos,0.5)).result()==WorldCommitCore.Outcome.DUPLICATE,"duplicate ApplyId");
        check(new WorldReceiptJournal(file).get(pos).remainder()==journal.get(pos).remainder(),"restart restore remainder");
        // Vanilla break once; successful re-read; zero-state cleanup.
        core.submit(hit("break-1",pos,500));
        check(core.tick(fake,103).get(0).result()==WorldCommitCore.Outcome.APPLIED_BREAK,"break confirmed");
        check(fake.drops==1&&fake.breaks==1,"one break, one drop");
        check(journal.get(pos)==null,"old damage erased after break");
        check(core.submit(hit("break-1",pos,500)).result()==WorldCommitCore.Outcome.DUPLICATE,"break not replayed");
        // Existing state was replaced with a block of the same fingerprint: reject.
        var replaced=new Fake();replaced.incarnation="incarnation-2";
        core.submit(hit("replace-1",key(3),500));
        check(core.tick(replaced,104).get(0).result()==WorldCommitCore.Outcome.ABORTED,"same state replacement rejected");
        // Claims/event cancellation remain vetoes; ALL World block types are eligible.
        var denied=new Fake();denied.deny=true;
        core.submit(hit("deny",key(4),500));
        check(core.tick(denied,105).get(0).result()==WorldCommitCore.Outcome.ABORTED,"claim veto");
        var canceled=new Fake();canceled.cancel=true;
        core.submit(hit("cancel",key(5),500));
        check(core.tick(canceled,106).get(0).result()==WorldCommitCore.Outcome.ABORTED,"event veto");
        var be=new Fake();be.be=true;
        core.submit(hit("be",key(6),500));
        check(core.tick(be,107).get(0).result()==WorldCommitCore.Outcome.APPLIED_BREAK,"BE vanilla World break");
        check(be.breaks==1&&be.drops==1,"BE single vanilla drop path");
        var multi=new Fake();multi.multiblock=true;
        core.submit(hit("multi",key(7),500));
        check(core.tick(multi,108).get(0).result()==WorldCommitCore.Outcome.APPLIED_BREAK,"linked World block vanilla break");
        check(multi.breaks==1&&multi.drops==1,"linked World block single break path");
        // World-only policy also overrides negative vanilla destroy speed and
        // the definition-level UNBREAKABLE flag; Plot is a separate pipeline.
        MaterialProperties immune=new MaterialProperties(255,255,0,0,true);
        var bedrockIntent=new WorldCommitCore.Intent("bedrock",key(61),"incarnation-1",3000,
                immune,1,100,true);
        var bedrock=new Fake();bedrock.immune=true;
        core.submit(bedrockIntent);
        check(core.tick(bedrock,108).get(0).result()==WorldCommitCore.Outcome.APPLIED_BREAK,
                "World UNBREAKABLE material is eligible");
        check(bedrock.breaks==1&&bedrock.drops==1,"World unbreakable executed exactly once");
        var unloaded=new Fake();unloaded.loaded=false;
        core.submit(hit("unloaded",key(8),500));
        check(core.tick(unloaded,109).get(0).result()==WorldCommitCore.Outcome.DEFERRED,"unloaded deferred");
        check(core.queued()==1,"unloaded retained");
        unloaded.loaded=true;
        check(core.tick(unloaded,110).get(0).result()==WorldCommitCore.Outcome.APPLIED_BREAK,"deferred replay same run");
        // Interrupted PREPARED receipt must be quarantined after reboot, never invoked again.
        Path crashPath=dir.resolve("crash.dat");
        var interrupted=new WorldReceiptJournal(crashPath);
        interrupted.transition(new WorldReceiptJournal.Receipt("crash-id",key(9),
                WorldReceiptJournal.Status.PREPARED,"TEST_CRASH",111),null);
        var recovered=new WorldCommitCore(new WorldReceiptJournal(crashPath),DamageModel.Settings.defaults(),64);
        var crashedWorld=new Fake();crashedWorld.air=true;
        check(recovered.quarantineInterrupted(crashedWorld,112)==1,"restart quarantine");
        check(recovered.submit(hit("crash-id",key(9),500)).result()==WorldCommitCore.Outcome.DUPLICATE,
                "recovery duplicate blocked");
        check(crashedWorld.breaks==0&&crashedWorld.drops==0,"recovery no second vanilla call");
        // Wrong CRC or schema must never silently produce an empty journal.
        byte[] bytes=Files.readAllBytes(file);bytes[11]^=0x11;
        Files.write(dir.resolve("corrupt.dat"),bytes);
        boolean corruptedRejected=false;
        try{new WorldReceiptJournal(dir.resolve("corrupt.dat"));}catch(java.io.IOException expected){corruptedRejected=true;}
        check(corruptedRejected,"bad data rejected");
        // Plot owner must not enter the WORLD commit path.
        boolean plotRejected=false;
        try {new WorldCommitCore.Intent("plot",new SparseStateKey(DIM,"PLOT",
                "123e4567-e89b-12d3-a456-426614174000",1,1,1,1,"a|b"),"i",10,STONE,1,1,true);}
        catch(IllegalArgumentException expected){plotRejected=true;}
        check(plotRejected,"plot domain isolation");
        // Explicit cap: 1 World break per tick.
        var capped=new WorldCommitCore(new WorldReceiptJournal(dir.resolve("cap.dat")),
                DamageModel.Settings.defaults(),1);
        capped.submit(hit("cap-1",key(11),500));capped.submit(hit("cap-2",key(12),500));
        var cap1=capped.tick(new Fake(),114);
        check(cap1.stream().filter(d->d.result()==WorldCommitCore.Outcome.APPLIED_BREAK).count()==1,
                "per tick world break cap");
        check(capped.queued()==1,"cap defers second break");
        check(capped.tick(new Fake(),115).stream().anyMatch(d->d.result()==WorldCommitCore.Outcome.APPLIED_BREAK),
                "cap backlog progresses");
        // CAS after claim/event hook: any changed observation aborts before break.
        var raced=new Fake();raced.flipAfterCanBreak=true;
        core.submit(hit("race-cas",key(20),500));
        check(core.tick(raced,115).getFirst().result()==WorldCommitCore.Outcome.ABORTED,"post-event CAS veto");
        check(raced.breaks==0,"no break after CAS veto");
        // Even when world is air, a false destruction result cannot be claimed APPLIED.
        var failedApi=new Fake();failedApi.breakReportsFalse=true;
        core.submit(hit("inconclusive",key(21),500));
        check(core.tick(failedApi,116).getFirst().result()==WorldCommitCore.Outcome.QUARANTINED,
                "API disagreement quarantined");
        // Expired unloaded deferred work cannot be indefinitely kept or replayed.
        var expired=new Fake();expired.loaded=false;
        core.submit(hit("expired",key(22),500));
        check(core.tick(expired,100+WorldCommitCore.MAX_DEFER_TICKS+1).getFirst().result()==
                WorldCommitCore.Outcome.ABORTED,"deferred TTL expiration");
        check(core.queued()==0,"expired queue cleanup");
        // No game API interaction is permitted away from the server thread.
        var wrongThread=new Fake();wrongThread.thread=false;
        boolean threadRejected=false;
        try{core.tick(wrongThread,117);}catch(IllegalStateException expected){threadRejected=true;}
        check(threadRejected,"server thread veto");
        System.out.println("WORLD_COMMIT_CORE_SELFTEST PASS checks="+checks+" WARNING=NO_MINECRAFT_WORLD_API_VERIFICATION");
    }
}
