package com.physicaldestroydeform.world;

import com.physicaldestroydeform.plot.PlotKeyCodec;
import com.physicaldestroydeform.state.SparseStateKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class LineageRepairTest {
    @TempDir Path temp;
    static final String DIM="minecraft:overworld";
    static final String NBT="1".repeat(64);
    private WorldPersistentJournal journal(String name)throws IOException{
        return new WorldPersistentJournal(temp.resolve(name),temp.resolve(name+".absent"));
    }
    private SparseStateKey plot(UUID owner,int x){
        return PlotKeyCodec.storage(new SparseStateKey(DIM,"PLOT",owner.toString(),1,x,-2,0,"minecraft:gold_block|gold"));
    }
    private WorldReceiptJournal.State seed(WorldPersistentJournal j,SparseStateKey key,int damage)throws IOException{
        var state=new WorldReceiptJournal.State(key,"0-source",damage,203,9,42,0);
        j.transition(new WorldReceiptJournal.Receipt("seed:"+key,key,WorldReceiptJournal.Status.APPLIED,"seed",42),state);
        return state;
    }
    private LineageJournal.Transfer prepare(WorldPersistentJournal w,WorldPersistentJournal p)throws IOException{
        var from=plot(UUID.randomUUID(),-2);var to=plot(UUID.randomUUID(),0);
        return LineageJournal.prepare("move",w,p,seed(w,from,354),to,50);
    }
    private static void replace(WorldPersistentJournal j,SparseStateKey k)throws IOException{
        j.replaceBlock(k.dimension(),k.x(),k.y(),k.z());
    }
    private static LineageJournal.Proof proof(){
        return new LineageJournal.Proof(true,true,true,true,true,true,1024,64,1024,NBT);
    }
    private static LineageJournal.RecoveryProof live(String incarnation){
        return new LineageJournal.RecoveryProof(true,true,"0-source",incarnation,NBT);
    }
    @Test void parentRemovedSetterRetiresExactSource()throws Exception{
        var j=journal("plot");var t=prepare(j,j);replace(j,t.destination());
        var sealed=LineageJournal.seal(t,j,j,"1-destination",proof(),51);
        assertTrue(LineageJournal.finishSealed(sealed,j,j,51));
        assertNull(j.get(t.source().key()));assertEquals(1,LineageJournal.revision(j,t.source().key()));
        var next=j.get(t.destination());assertEquals(354,next.damage());assertEquals(203,next.remainder());
        assertEquals(0,next.deformation());assertEquals(9,next.materialGeneration());
    }
    @Test void priorReplacementNoDoubleIncrement()throws Exception{
        var j=journal("plot");var t=prepare(j,j);replace(j,t.source().key());replace(j,t.destination());
        var sealed=LineageJournal.seal(t,j,j,"1-destination",proof(),51);
        assertTrue(LineageJournal.finishSealed(sealed,j,j,51));
        assertFalse(LineageJournal.finishSealed(sealed,j,j,51));
        assertEquals(1,LineageJournal.revision(j,t.source().key()));
    }
    @Test void staleChangedSourceRejectedBeforeRetirement()throws Exception{
        var j=journal("plot");var t=prepare(j,j);replace(j,t.destination());
        var k=t.source().key();var newer=new WorldReceiptJournal.State(k,"0-source",355,203,9,42,0);
        j.transition(new WorldReceiptJournal.Receipt("new-source",k,WorldReceiptJournal.Status.APPLIED,"change",50),newer);
        assertThrows(IOException.class,()->LineageJournal.seal(t,j,j,"1-destination",proof(),51));
        assertEquals(newer,j.get(k));assertEquals(0,LineageJournal.revision(j,k));
    }
    @Test void sourceRevisionAheadRejected()throws Exception{
        var j=journal("plot");var t=prepare(j,j);replace(j,t.source().key());replace(j,t.source().key());replace(j,t.destination());
        assertThrows(IOException.class,()->LineageJournal.seal(t,j,j,"1-destination",proof(),51));
        assertEquals(2,LineageJournal.revision(j,t.source().key()));
    }
    @Test void destinationOccupiedRejectedBeforeSourceRetirement()throws Exception{
        var j=journal("plot");var t=prepare(j,j);replace(j,t.destination());seed(j,t.destination(),99);
        assertThrows(IOException.class,()->LineageJournal.seal(t,j,j,"1-destination",proof(),51));
        assertEquals(t.source(),j.get(t.source().key()));
    }
    @Test void destinationRevisionMismatchRejectedBeforeSourceRetirement()throws Exception{
        var j=journal("plot");var t=prepare(j,j);replace(j,t.destination());replace(j,t.destination());
        assertThrows(IOException.class,()->LineageJournal.seal(t,j,j,"2-destination",proof(),51));
        assertEquals(t.source(),j.get(t.source().key()));
    }
    @Test void missingSourceSetterRejected()throws Exception{
        var j=journal("plot");var t=prepare(j,j);replace(j,t.destination());
        var bad=new LineageJournal.Proof(false,true,true,true,true,true,1024,64,1024,NBT);
        assertThrows(IOException.class,()->LineageJournal.seal(t,j,j,"1-destination",bad,51));
        assertEquals(t.source(),j.get(t.source().key()));
    }
    @Test void missingDestinationTrackerUsesWitnessedGuardedIncrement()throws Exception{
        var j=journal("plot");var t=prepare(j,j);
        var sealed=LineageJournal.seal(t,j,j,"0-destination",proof(),51);
        LineageJournal.finishSealed(sealed,j,j,51);
        assertEquals(1,LineageJournal.revision(j,t.destination()));
        assertEquals("1-destination",j.get(t.destination()).incarnation());
    }
    @Test void exactCopycatBeMismatchRejected()throws Exception{
        var j=journal("plot");var t=prepare(j,j);replace(j,t.destination());
        var bad=new LineageJournal.Proof(true,true,true,true,false,true,1024,64,1024,NBT);
        assertThrows(IOException.class,()->LineageJournal.seal(t,j,j,"1-destination",bad,51));
    }
    @Test void revisionMaxCannotBeRetired()throws Exception{
        var j=journal("plot");var t=prepare(j,j);
        var bad=new LineageJournal.Transfer(t.id(),t.source(),t.destination(),Long.MAX_VALUE,t.toRevision());
        assertThrows(IOException.class,()->LineageJournal.seal(bad,j,j,"0-destination",proof(),51));
        assertEquals(t.source(),j.get(t.source().key()));
    }
    @Test void crashBeforeProofQuarantinesWithoutReplay()throws Exception{
        var w=journal("source");var p=journal("dest");var t=prepare(w,p);
        var ww=journal("source");var pp=journal("dest");
        assertNull(LineageJournal.decode(ww.receipt(t.outId()),pp.receipt(t.inId())));
        LineageJournal.quarantine(ww,t.outId(),60);LineageJournal.quarantine(pp,t.inId(),60);
        assertEquals(WorldReceiptJournal.Status.PARTIAL_QUARANTINED,ww.receipt(t.outId()).status());
        assertEquals(t.source(),ww.get(t.source().key()));assertNull(pp.get(t.destination()));
    }
    @Test void crashAfterProofBeforeRetireResumesExactlyOnce()throws Exception{
        var w=journal("source");var p=journal("dest");var t=prepare(w,p);replace(p,t.destination());
        LineageJournal.seal(t,w,p,"1-destination",proof(),51);
        var ww=journal("source");var pp=journal("dest");
        var sealed=LineageJournal.decode(ww.receipt(t.outId()),pp.receipt(t.inId()));assertNotNull(sealed);
        assertTrue(LineageJournal.resume(sealed,ww,pp,live("1-destination"),60));
        assertFalse(LineageJournal.resume(sealed,ww,pp,live("1-destination"),61));
        assertEquals(354,pp.get(t.destination()).damage());assertNull(ww.get(t.source().key()));
    }
    @Test void crashAfterOutBeforeInResumesExactlyOnce()throws Exception{
        var w=journal("source");var p=journal("dest");var t=prepare(w,p);replace(p,t.destination());
        var sealed=LineageJournal.seal(t,w,p,"1-destination",proof(),51);
        w.retireLineageSource(t); // exact source CAS then fsynced OUT before IN
        w.transition(new WorldReceiptJournal.Receipt(t.outId(),t.source().key(),WorldReceiptJournal.Status.APPLIED,sealed.reason(),51),null);
        var ww=journal("source");var pp=journal("dest");
        var recovered=LineageJournal.decode(ww.receipt(t.outId()),pp.receipt(t.inId()));assertNotNull(recovered);
        assertTrue(LineageJournal.resume(recovered,ww,pp,live("1-destination"),60));
        var w3=journal("source");var p3=journal("dest");
        assertFalse(LineageJournal.resume(recovered,w3,p3,live("1-destination"),61));
        assertEquals(1,LineageJournal.revision(w3,t.source().key()));
    }
    @Test void partialProofNoReplay()throws Exception{
        var w=journal("source");var p=journal("dest");var t=prepare(w,p);replace(p,t.destination());
        var sealed=LineageJournal.seal(t,w,p,"1-destination",proof(),51);
        p.transition(new WorldReceiptJournal.Receipt(t.inId(),t.destination(),WorldReceiptJournal.Status.PREPARED,"LINEAGE_PREPARED:354:203:0:9:42:0:0",51),null);
        assertNull(LineageJournal.decode(w.receipt(t.outId()),p.receipt(t.inId())));
        assertThrows(IOException.class,()->LineageJournal.resume(sealed,w,p,live("1-destination"),60));
        assertEquals(t.source(),w.get(t.source().key()));
    }
    @Test void legacyQuarantineReadableNotAutomaticallyResurrected()throws Exception{
        var j=journal("plot");var t=prepare(j,j);
        LineageJournal.quarantine(j,t.outId(),51);LineageJournal.quarantine(j,t.inId(),51);
        var loaded=journal("plot");
        assertNull(LineageJournal.decode(loaded.receipt(t.outId()),loaded.receipt(t.inId())));
        assertThrows(IOException.class,()->LineageJournal.seal(t,loaded,loaded,"0-destination",proof(),60));
        assertEquals(t.source(),loaded.get(t.source().key()));
    }
    @Test void changedDestinationNoReplay()throws Exception{
        var j=journal("plot");var t=prepare(j,j);replace(j,t.destination());
        var sealed=LineageJournal.seal(t,j,j,"1-destination",proof(),51);
        var changed=new LineageJournal.RecoveryProof(true,true,"0-source","1-other",NBT);
        assertThrows(IOException.class,()->LineageJournal.resume(sealed,j,j,changed,60));
        assertEquals(t.source(),j.get(t.source().key()));assertNull(j.get(t.destination()));
    }
    @Test void invalidSourceAndPayloadCannotReplayEvenWithProofPair()throws Exception{
        var j=journal("plot");var t=prepare(j,j);replace(j,t.destination());
        var sealed=LineageJournal.seal(t,j,j,"1-destination",proof(),51);
        assertThrows(IOException.class,()->LineageJournal.resume(sealed,j,j,new LineageJournal.RecoveryProof(false,true,"0-source","1-destination",NBT),60));
        assertThrows(IOException.class,()->LineageJournal.resume(sealed,j,j,new LineageJournal.RecoveryProof(true,true,"0-source","1-destination","2".repeat(64)),60));
        assertEquals(t.source(),j.get(t.source().key()));
    }
    @Test void durableRecoveryClockAndBoundedMarker()throws Exception{
        var j=journal("plot");var t=prepare(j,j);replace(j,t.destination());
        var sealed=LineageJournal.seal(t,j,j,"1-destination",proof(),51);
        assertEquals(51,sealed.proofTick());assertTrue(sealed.reason().length()<=500);
        assertFalse(LineageRecovery.expired(51+WorldCommitCore.MAX_DEFER_TICKS,51));
        assertTrue(LineageRecovery.expired(52+WorldCommitCore.MAX_DEFER_TICKS,51));
        assertTrue(LineageRecovery.expired(50,51));
    }
    @Test void legacyFinishCannotBypassProof()throws Exception{
        var j=journal("plot");var t=prepare(j,j);replace(j,t.source().key());replace(j,t.destination());
        LineageJournal.seal(t,j,j,"1-destination",proof(),51);
        assertThrows(IOException.class,()->LineageJournal.finish(t,j,j,"1-destination",52));
        assertNull(j.get(t.destination()));
    }
    @Test void emptySourceAtOldRevisionIsNotRetired()throws Exception{
        var j=journal("plot");var t=prepare(j,j);replace(j,t.destination());
        var k=t.source().key();j.discardAtPosition(k.dimension(),k.x(),k.y(),k.z());
        assertThrows(IOException.class,()->LineageJournal.seal(t,j,j,"1-destination",proof(),51));
        assertEquals(0,LineageJournal.revision(j,k));assertNull(j.get(t.destination()));
    }
    @Test void mismatchedProofIntegrityRejected()throws Exception{
        var j=journal("plot");var t=prepare(j,j);replace(j,t.destination());
        var sealed=LineageJournal.seal(t,j,j,"1-destination",proof(),51);
        String tampered=sealed.reason().replace(":354:",":355:");
        j.transition(new WorldReceiptJournal.Receipt(t.outId(),t.source().key(),WorldReceiptJournal.Status.PREPARED,tampered,51),null);
        j.transition(new WorldReceiptJournal.Receipt(t.inId(),t.destination(),WorldReceiptJournal.Status.PREPARED,tampered,51),null);
        assertNull(LineageJournal.decode(j.receipt(t.outId()),j.receipt(t.inId())));
        assertEquals(t.source(),j.get(t.source().key()));
    }
    @Test void directFinishAndResumeRejectClockRollback()throws Exception{
        var j=journal("plot");var t=prepare(j,j);replace(j,t.destination());
        var sealed=LineageJournal.seal(t,j,j,"1-destination",proof(),51);
        assertThrows(IOException.class,()->LineageJournal.finishSealed(sealed,j,j,50));
        assertThrows(IOException.class,()->LineageJournal.resume(sealed,j,j,live("1-destination"),50));
        assertEquals(t.source(),j.get(t.source().key()));
    }
}
