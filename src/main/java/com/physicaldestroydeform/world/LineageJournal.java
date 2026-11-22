package com.physicaldestroydeform.world;

import com.physicaldestroydeform.plot.PlotKeyCodec;
import com.physicaldestroydeform.state.SparseStateKey;
import java.io.IOException;
import java.util.Objects;
import static com.physicaldestroydeform.world.WorldReceiptJournal.Status.*;

/**
 * Two durable receipt legs using the existing schema-6 journals. Native movement
 * owns block writes; only its exact setter witnesses may authorize finish().
 * An interrupted transaction is never replayed as a second native move.
 */
public final class LineageJournal {
    public static final String PREFIX="LINEAGE:1:";
    private LineageJournal(){}
    public record Transfer(String id,WorldReceiptJournal.State source,SparseStateKey destination,
                           long fromRevision,long toRevision) {
        public String outId(){return PREFIX+id+":OUT";}
        public String inId(){return PREFIX+id+":IN";}
    }
    public static boolean isMarker(WorldReceiptJournal.Receipt r){return r!=null&&r.id().startsWith(PREFIX);}
    /** Expire an uncertain cell only after a later proven replacement, not merely
     * because time passed or a block with the same fingerprint reappeared. */
    public static long quarantineThrough(WorldReceiptJournal.Receipt r){
        if(!isMarker(r)||!r.reason().startsWith("LINEAGE_PREPARED:"))return Long.MAX_VALUE;
        try{
            String[] a=r.reason().split(":");
            return Math.addExact(Long.parseLong(a[r.id().endsWith(":OUT")?6:7]),1L);
        }catch(RuntimeException invalid){return Long.MAX_VALUE;}
    }
    private static String dimension(SparseStateKey k){return PlotKeyCodec.isPlotStorage(k)?PlotKeyCodec.real(k).dimension():k.dimension();}
    private static String blockId(SparseStateKey k){return k.fingerprint().split("\\|",2)[0];}
    private static String payload(WorldReceiptJournal.State before,long from,long to){
        return "LINEAGE_PREPARED:"+before.damage()+":"+before.remainder()+":"+before.deformation()
                +":"+before.materialGeneration()+":"+before.tick()+":"+from+":"+to;
    }
    /** Over-cap or otherwise unproven movement retains a durable before-image
     * without authorizing a destination. Old keys can still be tombstoned.
     * Unlike pending transfer legs, this marker cannot be finalized. */
    public static String quarantineSource(WorldPersistentJournal journal,WorldReceiptJournal.State before,long tick,String reason)throws IOException{
        if(!Objects.equals(journal.get(before.key()),before))throw new IOException("LINEAGE_UNRESOLVED_SOURCE_CAS");
        long revision=revision(journal,before.key());
        String id=PREFIX+java.util.UUID.randomUUID()+":OUT";
        journal.transition(new WorldReceiptJournal.Receipt(id,before.key(),PARTIAL_QUARANTINED,
                payload(before,revision,0)+":UNCERTAIN:"+reason,tick),null);
        return id;
    }
    public static Transfer prepare(String id,WorldPersistentJournal source,WorldPersistentJournal destination,
                                   WorldReceiptJournal.State before,SparseStateKey target,long tick)throws IOException {
        Objects.requireNonNull(before);Objects.requireNonNull(target);
        var old=before.key();
        long from=revision(source,old),to=revision(destination,target);
        var t=new Transfer(id,before,target,from,to);
        if(id==null||id.isBlank()||id.length()>120||old.equals(target)
                ||!dimension(old).equals(dimension(target))||!blockId(old).equals(blockId(target))
                ||!Objects.equals(source.get(old),before)||!before.incarnation().startsWith(from+"-")
                ||source.receipt(t.outId())!=null||destination.receipt(t.inId())!=null
                ||from==Long.MAX_VALUE||to==Long.MAX_VALUE
                ||destination.hasPosition(target.dimension(),target.x(),target.y(),target.z())
                ||source.admissionRejectReason()!=null||destination.admissionRejectReason()!=null
                ||!destination.canStore(target))
            throw new IOException("LINEAGE_PREPARE_CAS");
        // Before-image quantities remain durable even if the native source is
        // already cleared when Minecraft/PDD next start. No replay by proximity.
        String payload=payload(before,from,to);
        source.transition(new WorldReceiptJournal.Receipt(t.outId(),old,PREPARED,payload,tick),null);
        try{
            destination.transition(new WorldReceiptJournal.Receipt(t.inId(),target,PREPARED,payload,tick),null);
        }catch(IOException|RuntimeException fail){
            quarantine(source,t.outId(),tick);
            throw fail;
        }
        return t;
    }
    public static boolean finish(Transfer t,WorldPersistentJournal source,WorldPersistentJournal destination,
                                 String incarnation,long tick)throws IOException {
        var old=t.source().key();var target=t.destination();
        var out=source.receipt(t.outId());var in=destination.receipt(t.inId());
        if(out!=null&&out.status()==APPLIED&&in!=null&&in.status()==APPLIED)return false;
        if(out==null||in==null||out.status()!=PREPARED||in.status()!=PREPARED
                ||!out.key().equals(old)||!in.key().equals(target)
                ||source.hasPosition(old.dimension(),old.x(),old.y(),old.z())
                ||destination.hasPosition(target.dimension(),target.x(),target.y(),target.z())
                ||revision(source,old)!=t.fromRevision()+1||revision(destination,target)!=t.toRevision()+1
                ||incarnation==null||!incarnation.startsWith((t.toRevision()+1)+"-"))
            throw new IOException("LINEAGE_POSTSTATE_CAS");
        var s=t.source();
        // Source tombstone MUST be durable first; no duplicated PDD state when
        // crossing two independent journals. APPLIED IN writes receipt+state atomically.
        source.transition(new WorldReceiptJournal.Receipt(t.outId(),old,APPLIED,"LINEAGE_NATIVE_POSTSTATE",tick),null);
        destination.transition(new WorldReceiptJournal.Receipt(t.inId(),target,APPLIED,"LINEAGE_NATIVE_POSTSTATE",tick),
                new WorldReceiptJournal.State(target,incarnation,s.damage(),s.remainder(),s.materialGeneration(),s.tick(),s.deformation()));
        return true;
    }
    public static void quarantine(WorldPersistentJournal journal,String id,long tick)throws IOException{
        var r=journal.receipt(id);
        if(isMarker(r)&&r.status()==PREPARED)
            journal.transition(new WorldReceiptJournal.Receipt(id,r.key(),PARTIAL_QUARANTINED,r.reason()+":UNCERTAIN",tick),null);
    }
    public static long revision(WorldPersistentJournal journal,SparseStateKey key){
        return journal.revision(key.dimension(),key.x(),key.y(),key.z());
    }
}
