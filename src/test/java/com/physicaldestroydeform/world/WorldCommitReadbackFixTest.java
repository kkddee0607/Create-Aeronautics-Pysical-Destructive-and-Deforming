package com.physicaldestroydeform.world;

import com.physicaldestroydeform.DamageModel;
import com.physicaldestroydeform.MaterialProperties;
import com.physicaldestroydeform.state.SparseStateKey;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Pure Java fake-gateway regression: no assertion about Sable's native COM or network. */
class WorldCommitReadbackFixTest {
    @TempDir Path temp;
    private static final SparseStateKey KEY=new SparseStateKey("minecraft:overworld","WORLD",
            "minecraft:overworld",1,1,64,1,"minecraft:dirt");
    private static final MaterialProperties MATERIAL=new MaterialProperties(30,80,0,0,false);
    private static final class Fake implements WorldCommitCore.WorldGateway {
        int breaks,drops,observations;
        boolean air,apiResult=true,vanishAfterBreak,throwFromObserver,throwFromBreak;
        boolean exceptionObserved;
        WorldCommitCore.BreakReadback status;
        @Override public boolean isServerThread(){return true;}
        @Override public WorldCommitCore.Observation readIfLoaded(SparseStateKey k){
            if(vanishAfterBreak && breaks>0)return null;
            return new WorldCommitCore.Observation(k.dimension(),k.fingerprint(),k.ownerGeneration(),
                    "incarnation",true,air,false,false,false,false);
        }
        @Override public WorldCommitCore.Permission canDamage(SparseStateKey k,WorldCommitCore.Observation o){
            return WorldCommitCore.Permission.ALLOW;
        }
        @Override public WorldCommitCore.Permission canBreak(SparseStateKey k,WorldCommitCore.Observation o){
            return WorldCommitCore.Permission.ALLOW;
        }
        @Override public boolean breakVanillaOnce(SparseStateKey k){
            breaks++;drops++;air=true;
            if(throwFromBreak)throw new IllegalStateException("mutated before exception");
            return apiResult;
        }
        @Override public void onBreakException(SparseStateKey k,RuntimeException failure){
            exceptionObserved=true;
        }
        @Override public void onBreakReadback(SparseStateKey k,boolean result,
                WorldCommitCore.Observation readback,WorldCommitCore.BreakReadback diagnostic){
            observations++;status=diagnostic;
            if(throwFromObserver)throw new IllegalStateException("diagnostic must be harmless");
        }
    }
    private WorldCommitCore.Decision execute(String id,Fake fake) throws Exception {
        var journal=new WorldPersistentJournal(temp.resolve(id),temp.resolve(id+".legacy.wal"));
        var core=new WorldCommitCore(journal,DamageModel.Settings.defaults(),32);
        var intent=new WorldCommitCore.Intent(id,KEY,"incarnation",100000,MATERIAL,1,20,true);
        assertEquals(WorldCommitCore.Outcome.DEFERRED,core.submit(intent).result());
        var result=core.tick(fake,21).getFirst();
        assertEquals(1,fake.breaks);assertEquals(1,fake.drops);
        assertEquals(WorldCommitCore.Outcome.DUPLICATE,core.submit(intent).result());
        assertEquals(1,fake.breaks);
        return result;
    }
    @Test void vanishedOwnerAfterApiTrueMustQuarantineWithoutSecondDrop() throws Exception {
        var fake=new Fake();fake.vanishAfterBreak=true;
        var result=execute("vanished",fake);
        assertEquals(WorldCommitCore.Outcome.QUARANTINED,result.result());
        assertEquals(WorldReceiptJournal.Status.PARTIAL_QUARANTINED,result.receipt().status());
        assertEquals(WorldCommitCore.BreakReadback.OWNER_OR_CHUNK_UNAVAILABLE,fake.status);
    }
    @Test void falseApiResultCannotBePromotedByAirReadback() throws Exception {
        var fake=new Fake();fake.apiResult=false;
        assertEquals(WorldCommitCore.Outcome.QUARANTINED,execute("falseApi",fake).result());
        assertEquals(WorldCommitCore.BreakReadback.API_REJECTED,fake.status);
    }
    @Test void observerExceptionMustNotChangeConfirmedBreak() throws Exception {
        var fake=new Fake();fake.throwFromObserver=true;
        assertEquals(WorldCommitCore.Outcome.APPLIED_BREAK,execute("observer",fake).result());
        assertEquals(WorldCommitCore.BreakReadback.CONFIRMED_AIR,fake.status);
    }
    @Test void exceptionAfterMutationIsQuarantinedAndNotReplayed() throws Exception {
        var fake=new Fake();fake.throwFromBreak=true;
        var result=execute("exception",fake);
        assertEquals(WorldCommitCore.Outcome.QUARANTINED,result.result());
        assertEquals(true,fake.exceptionObserved);
        assertEquals(WorldReceiptJournal.Status.PARTIAL_QUARANTINED,result.receipt().status());
    }
    @Test void classificationNamesAllReadbackBranches(){
        var unloaded=new WorldCommitCore.Observation("x","y",1,"i",false,false,false,false,false,false);
        var remains=new WorldCommitCore.Observation("x","y",1,"i",true,false,false,false,false,false);
        var airBE=new WorldCommitCore.Observation("x","y",1,"i",true,true,true,false,false,false);
        assertEquals(WorldCommitCore.BreakReadback.CHUNK_UNLOADED,
                WorldCommitCore.classifyBreakReadback(true,unloaded));
        assertEquals(WorldCommitCore.BreakReadback.BLOCK_REMAINS,
                WorldCommitCore.classifyBreakReadback(true,remains));
        assertEquals(WorldCommitCore.BreakReadback.AIR_WITH_BLOCK_ENTITY,
                WorldCommitCore.classifyBreakReadback(true,airBE));
    }
}
