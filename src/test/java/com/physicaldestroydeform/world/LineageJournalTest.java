package com.physicaldestroydeform.world;

import com.physicaldestroydeform.plot.PlotKeyCodec;
import com.physicaldestroydeform.state.SparseStateKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class LineageJournalTest {
    @TempDir Path temp;
    private static final String DIM="minecraft:overworld";
    private SparseStateKey world(int x){return new SparseStateKey(DIM,"WORLD",DIM,1,x,64,0,"minecraft:gold_block|gold");}
    private SparseStateKey plot(UUID id,int x){return PlotKeyCodec.storage(new SparseStateKey(DIM,"PLOT",id.toString(),1,x,0,0,"minecraft:gold_block|gold"));}
    private WorldPersistentJournal journal(String name)throws IOException{return new WorldPersistentJournal(temp.resolve(name),temp.resolve(name+".absent"));}
    private WorldReceiptJournal.State seed(WorldPersistentJournal j,SparseStateKey k,int damage)throws IOException{
        var s=new WorldReceiptJournal.State(k,"0-test",damage,17,9,42,3);
        j.transition(new WorldReceiptJournal.Receipt("seed:"+k, k,WorldReceiptJournal.Status.APPLIED,"seed",42),s);return s;
    }
    @Test void splitPreservesResidualAndDistinctFragmentDamage()throws Exception {
        var j=journal("plot");var parent=UUID.randomUUID();var a=plot(parent,1);var b=plot(parent,2);var residual=plot(parent,3);
        var sa=seed(j,a,130);var sb=seed(j,b,270);var sr=seed(j,residual,450);
        var ad=plot(UUID.randomUUID(),0);var bd=plot(UUID.randomUUID(),0);
        var ta=LineageJournal.prepare("a",j,j,sa,ad,50);
        var tb=LineageJournal.prepare("b",j,j,sb,bd,50);
        j.replaceBlock(a.dimension(),a.x(),a.y(),a.z());j.replaceBlock(ad.dimension(),ad.x(),ad.y(),ad.z());
        j.replaceBlock(b.dimension(),b.x(),b.y(),b.z());j.replaceBlock(bd.dimension(),bd.x(),bd.y(),bd.z());
        LineageJournal.finish(ta,j,j,"1-destination",51);
        LineageJournal.finish(tb,j,j,"1-destination",51);
        assertNull(j.get(a));assertNull(j.get(b));assertEquals(sr,j.get(residual));
        assertEquals(130,j.get(ad).damage());assertEquals(270,j.get(bd).damage());
        assertEquals(17,j.get(ad).remainder());assertEquals(3,j.get(ad).deformation());assertEquals(9,j.get(ad).materialGeneration());
    }
    @Test void worldPlotRoundtripNeverDuplicates()throws Exception {
        var w=journal("world");var p=journal("plot");var a=world(10);var b=plot(UUID.randomUUID(),0);
        var t=LineageJournal.prepare("assemble",w,p,seed(w,a,101),b,50);
        w.replaceBlock(a.dimension(),a.x(),a.y(),a.z());p.replaceBlock(b.dimension(),b.x(),b.y(),b.z());
        LineageJournal.finish(t,w,p,"1-destination",51);
        assertFalse(LineageJournal.finish(t,w,p,"1-destination",51));
        var c=world(20);var u=LineageJournal.prepare("disassemble",p,w,p.get(b),c,55);
        p.replaceBlock(b.dimension(),b.x(),b.y(),b.z());w.replaceBlock(c.dimension(),c.x(),c.y(),c.z());
        LineageJournal.finish(u,p,w,"1-world",56);
        assertNull(w.get(a));assertNull(p.get(b));assertEquals(101,w.get(c).damage());
    }
    @Test void interruptedPrepareRemainsDurablyUncertainOnReload()throws Exception {
        var w=journal("world");var p=journal("plot");var a=world(10);var b=plot(UUID.randomUUID(),0);
        var t=LineageJournal.prepare("interrupted",w,p,seed(w,a,123),b,50);
        w.replaceBlock(a.dimension(),a.x(),a.y(),a.z());
        var ww=journal("world");var pp=journal("plot");
        assertTrue(LineageJournal.isMarker(ww.uncertain().getFirst()));
        assertTrue(LineageJournal.isMarker(pp.uncertain().getFirst()));
        assertNull(ww.get(a));assertNull(pp.get(b));
        assertThrows(IOException.class,()->LineageJournal.finish(t,ww,pp,"1-dest",51));
    }
    @Test void staleOrOccupiedDestinationRejected()throws Exception {
        var w=journal("world");var p=journal("plot");var a=world(10);var b=plot(UUID.randomUUID(),0);
        var state=seed(w,a,100);seed(p,b,200);
        assertThrows(IOException.class,()->LineageJournal.prepare("occupied",w,p,state,b,50));
        var c=plot(UUID.randomUUID(),0);
        w.replaceBlock(a.dimension(),a.x(),a.y(),a.z());
        assertThrows(IOException.class,()->LineageJournal.prepare("stale",w,p,state,c,50));
    }
    @Test void proofRequiresBothSourceAndDestinationRevisionChanges()throws Exception {
        var w=journal("world");var p=journal("plot");var a=world(10);var b=plot(UUID.randomUUID(),0);
        var t=LineageJournal.prepare("proof",w,p,seed(w,a,100),b,50);
        assertThrows(IOException.class,()->LineageJournal.finish(t,w,p,"1-dest",51));
        w.replaceBlock(a.dimension(),a.x(),a.y(),a.z());
        assertThrows(IOException.class,()->LineageJournal.finish(t,w,p,"1-dest",51));
        assertNull(p.get(b));
    }
    @Test void mergeDistinctPlotOwnersRetainsIndependentDamage()throws Exception{
        var j=journal("merge");
        var a=plot(UUID.randomUUID(),0);var b=plot(UUID.randomUUID(),0);
        UUID merged=UUID.randomUUID();var x=plot(merged,0);var y=plot(merged,1);
        var ta=LineageJournal.prepare("merge-a",j,j,seed(j,a,211),x,50);
        var tb=LineageJournal.prepare("merge-b",j,j,seed(j,b,322),y,50);
        for(var k:java.util.List.of(a,b,x,y))j.replaceBlock(k.dimension(),k.x(),k.y(),k.z());
        LineageJournal.finish(ta,j,j,"1-x",51);LineageJournal.finish(tb,j,j,"1-y",51);
        var loaded=journal("merge");
        assertNull(loaded.get(a));assertNull(loaded.get(b));
        assertEquals(211,loaded.get(x).damage());assertEquals(322,loaded.get(y).damage());
    }
    @Test void capUnresolvedBeforeImageSurvivesActualSourceTombstone()throws Exception{
        var j=journal("overflow");var a=world(1);var s=seed(j,a,901);
        String id=LineageJournal.quarantineSource(j,s,50,"TRANSFER_CAP");
        j.replaceBlock(a.dimension(),a.x(),a.y(),a.z());
        var loaded=journal("overflow");var receipt=loaded.receipt(id);
        assertNull(loaded.get(a));assertEquals(WorldReceiptJournal.Status.PARTIAL_QUARANTINED,receipt.status());
        assertTrue(receipt.reason().contains(":901:17:3:9:42:"));
        assertEquals(1,LineageJournal.quarantineThrough(receipt));
        loaded.replaceBlock(a.dimension(),a.x(),a.y(),a.z());
        assertTrue(LineageJournal.revision(loaded,a)>LineageJournal.quarantineThrough(receipt));
    }
    @Test void exactRotatedFingerprintAcceptedButDifferentBlockRejected()throws Exception{
        var w=journal("world-rotation");var p=journal("plot-rotation");var a=world(10);
        var state=seed(w,a,123);
        var wrong=new SparseStateKey(DIM,"WORLD",DIM,1,20,64,0,"minecraft:stone|stone");
        assertThrows(IOException.class,()->LineageJournal.prepare("wrong",w,w,state,wrong,50));
        var b=plot(UUID.randomUUID(),0);
        var rotated=new SparseStateKey(b.dimension(),b.ownerSpace(),b.ownerId(),b.ownerGeneration(),b.x(),b.y(),b.z(),"minecraft:gold_block|rotated");
        var t=LineageJournal.prepare("rotate",w,p,state,rotated,50);
        w.replaceBlock(a.dimension(),a.x(),a.y(),a.z());p.replaceBlock(rotated.dimension(),rotated.x(),rotated.y(),rotated.z());
        LineageJournal.finish(t,w,p,"1-rotated",51);
        assertEquals(123,p.get(rotated).damage());
    }
}
