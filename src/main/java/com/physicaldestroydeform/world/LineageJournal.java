package com.physicaldestroydeform.world;

import com.physicaldestroydeform.plot.PlotKeyCodec;
import com.physicaldestroydeform.state.SparseStateKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
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
    public record Proof(boolean sourceCleared,boolean destinationWritten,boolean mappingValid,
                        boolean destinationReadback,boolean payloadMatched,boolean sourceRemoved,
                        int sourceCenterX,int sourceCenterY,int sourceCenterZ,String payloadDigest){}
    public record RecoveryProof(boolean sourceClearedOrRemoved,boolean destinationLoadedExact,
                                String sourceIncarnation,String destinationIncarnation,String payloadDigest){}
    public record Sealed(Transfer transfer,String destinationIncarnation,String payloadDigest,
                         boolean sourceRemoved,int sourceCenterX,int sourceCenterY,int sourceCenterZ,long proofTick,String reason){}
    public static boolean isProofMarker(WorldReceiptJournal.Receipt r){
        return isMarker(r)&&r.reason().startsWith("LINEAGE_PREPARED:")&&r.reason().contains(":V2:");
    }
    static String digest(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private static String enc(String s){return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));}
    private static String dec(String s){return new String(Base64.getUrlDecoder().decode(s),StandardCharsets.UTF_8);}
    private static boolean hash(String s){return s!=null&&s.matches("[0-9a-f]{64}");}
    private static String pairHash(String id,SparseStateKey old,SparseStateKey target,String reason){
        return digest(id+":"+enc(old.toString())+":"+enc(target.toString())+":"+reason);
    }
    private static String destinationPostIncarnation(Transfer t,WorldPersistentJournal journal,String live)throws IOException{
        long rev=revision(journal,t.destination());
        if(live==null||!live.startsWith(rev+"-")||rev!=t.toRevision()&&rev!=t.toRevision()+1)
            throw new IOException("LINEAGE_DESTINATION_INCARNATION_CAS");
        return (t.toRevision()+1)+live.substring(live.indexOf('-'));
    }
    public static Sealed seal(Transfer t,WorldPersistentJournal source,WorldPersistentJournal destination,
                              String liveDestination,Proof proof,long tick)throws IOException{
        if(proof==null||!proof.sourceCleared()||!proof.destinationWritten()||!proof.mappingValid()
                ||!proof.destinationReadback()||!proof.payloadMatched()||!hash(proof.payloadDigest()))
            throw new IOException("LINEAGE_NATIVE_PROOF_INCOMPLETE");
        var out=source.receipt(t.outId());var in=destination.receipt(t.inId());
        String base=payload(t.source(),t.fromRevision(),t.toRevision());
        if(out==null||in==null||out.status()!=PREPARED||in.status()!=PREPARED
                ||!out.reason().equals(base)||!in.reason().equals(base))
            throw new IOException("LINEAGE_PREPARED_PAIR_CAS");
        source.checkLineageSource(t);destination.checkLineageDestination(t);
        if(source==destination)source.checkLineageJointCapacity(t);
        String inc=destinationPostIncarnation(t,destination,liveDestination);
        String prefix=base+":V2:"+enc(t.source().incarnation())+":"+enc(inc)+":"+proof.payloadDigest()
                +":"+(proof.sourceRemoved()?1:0)+":"+proof.sourceCenterX()+":"+proof.sourceCenterY()+":"+proof.sourceCenterZ()+":"+tick;
        String reason=prefix+":"+pairHash(t.id(),t.source().key(),t.destination(),prefix);
        if(reason.length()>500)throw new IOException("LINEAGE_PROOF_SIZE");
        source.transition(new WorldReceiptJournal.Receipt(out.id(),out.key(),PREPARED,reason,tick),null);
        destination.transition(new WorldReceiptJournal.Receipt(in.id(),in.key(),PREPARED,reason,tick),null);
        return new Sealed(t,inc,proof.payloadDigest(),proof.sourceRemoved(),proof.sourceCenterX(),proof.sourceCenterY(),proof.sourceCenterZ(),tick,reason);
    }
    public static Sealed decode(WorldReceiptJournal.Receipt out,WorldReceiptJournal.Receipt in){
        if(!isProofMarker(out)||!isProofMarker(in)||!out.id().endsWith(":OUT")||!in.id().endsWith(":IN")
                ||!out.id().substring(0,out.id().length()-4).equals(in.id().substring(0,in.id().length()-3))
                ||!out.reason().equals(in.reason())||(out.status()!=PREPARED&&out.status()!=APPLIED)
                ||(in.status()!=PREPARED&&in.status()!=APPLIED)||in.status()==APPLIED&&out.status()!=APPLIED)return null;
        try{
            String[] a=out.reason().split(":",-1);
            if(a.length!=18||!a[8].equals("V2")||!hash(a[11])||!hash(a[17])||!a[12].matches("[01]"))return null;
            String id=out.id().substring(PREFIX.length(),out.id().length()-4);
            String prefix=out.reason().substring(0,out.reason().lastIndexOf(':'));
            if(!a[17].equals(pairHash(id,out.key(),in.key(),prefix))||out.key().equals(in.key())
                    ||!dimension(out.key()).equals(dimension(in.key()))||!blockId(out.key()).equals(blockId(in.key())))return null;
            long from=Long.parseLong(a[6]),to=Long.parseLong(a[7]);
            String src=dec(a[9]),dst=dec(a[10]);
            if(from<0||to<0||from==Long.MAX_VALUE||to==Long.MAX_VALUE
                    ||!src.startsWith(from+"-")||!dst.startsWith((to+1)+"-"))return null;
            var state=new WorldReceiptJournal.State(out.key(),src,Integer.parseInt(a[1]),Integer.parseInt(a[2]),
                    Long.parseLong(a[4]),Long.parseLong(a[5]),Integer.parseInt(a[3]));
            if(state.damage()==0&&state.remainder()==0&&state.deformation()==0)return null;
            long proofTick=Long.parseLong(a[16]);
            if(proofTick<0||out.tick()<proofTick||in.tick()<proofTick)return null;
            return new Sealed(new Transfer(id,state,in.key(),from,to),dst,a[11],a[12].equals("1"),
                    Integer.parseInt(a[13]),Integer.parseInt(a[14]),Integer.parseInt(a[15]),proofTick,out.reason());
        }catch(RuntimeException bad){return null;}
    }
    private static boolean complete(Transfer t,WorldPersistentJournal source,WorldPersistentJournal destination){
        var out=source.receipt(t.outId());var in=destination.receipt(t.inId());
        return out!=null&&in!=null&&out.status()==APPLIED&&in.status()==APPLIED;
    }
    public static boolean finishSealed(Sealed s,WorldPersistentJournal source,WorldPersistentJournal destination,long tick)throws IOException{
        var t=s.transfer();
        var out=source.receipt(t.outId());var in=destination.receipt(t.inId());
        if(!s.equals(decode(out,in)))throw new IOException("LINEAGE_PROOF_PAIR_CAS");
        if(complete(t,source,destination))return false;
        if(tick<s.proofTick())throw new IOException("LINEAGE_PROOF_CLOCK_ROLLBACK");
        source.checkLineageSource(t);destination.checkLineageDestination(t);
        if(source==destination)source.checkLineageJointCapacity(t);
        source.retireLineageSource(t);destination.retireLineageDestination(t);
        var before=t.source();
        if(out.status()==PREPARED)source.transition(new WorldReceiptJournal.Receipt(out.id(),out.key(),APPLIED,s.reason(),tick),null);
        destination.transition(new WorldReceiptJournal.Receipt(in.id(),in.key(),APPLIED,s.reason(),tick),
                new WorldReceiptJournal.State(t.destination(),s.destinationIncarnation(),before.damage(),before.remainder(),
                        before.materialGeneration(),before.tick(),before.deformation()));
        return true;
    }
    public static boolean resume(Sealed s,WorldPersistentJournal source,WorldPersistentJournal destination,
                                 RecoveryProof proof,long tick)throws IOException{
        var t=s.transfer();
        if(!s.equals(decode(source.receipt(t.outId()),destination.receipt(t.inId()))))
            throw new IOException("LINEAGE_RECOVERY_PAIR_CAS");
        if(complete(t,source,destination))return false;
        if(LineageRecovery.expired(tick,s.proofTick()))throw new IOException("LINEAGE_RECOVERY_EXPIRED_OR_CLOCK_ROLLBACK");
        if(proof==null||!proof.sourceClearedOrRemoved()||!proof.destinationLoadedExact()
                ||!t.source().incarnation().equals(proof.sourceIncarnation())||!s.payloadDigest().equals(proof.payloadDigest())
                ||!s.destinationIncarnation().equals(destinationPostIncarnation(t,destination,proof.destinationIncarnation())))
            throw new IOException("LINEAGE_RECOVERY_LIVE_CAS");
        return finishSealed(s,source,destination,tick);
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
            // An IO exception may follow a completed append but precede
            // the in-memory publication. Do not overwrite uncertain WAL.
            if(!(fail instanceof IOException))quarantine(source,t.outId(),tick);
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
                ||!payload(t.source(),t.fromRevision(),t.toRevision()).equals(out.reason())
                ||!out.reason().equals(in.reason())
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
