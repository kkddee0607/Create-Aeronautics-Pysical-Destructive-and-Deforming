package com.physicaldestroydeform.deform;

import com.physicaldestroydeform.MaterialProperties;
import com.physicaldestroydeform.BlockDamageState;
import com.physicaldestroydeform.state.SparseStateKey;
import com.physicaldestroydeform.world.WorldReceiptJournal;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.CRC32C;
import static java.nio.file.StandardOpenOption.*;

/** Two-cell before-images, separate from the one-cell damage ledger. No world writes.
 * A recovered PREPARED is uncertain: never execute its setters or infer success
 * from matching block states. Keep both cells fenced until explicit reconciliation.
 */
public final class MoveTransactionJournal {
    public static final int MAX_RECORDS=8192,MAX_RECORD_BYTES=16*1024;
    public static final long MAX_STORE_BYTES=64L*1024*1024;
    private static final int MAGIC=0x50444d36,SCHEMA=2;
    public enum Phase { PREPARED, APPLIED, ROLLED_BACK, QUARANTINED, BLOCKED_PENDING }
    public record BeforeImage(String id,String directApplyId,SparseStateKey source,
            SparseStateKey destinationBefore,WorldReceiptJournal.State sourceState,
            WorldReceiptJournal.State destinationAfter,long sourceRevision,long destinationRevision,
            long createdGameTick,long expiresGameTick,long transformEpoch,double reservedQ,
            MaterialProperties material,String materialRevision,String encodedBePayload,
            double blockedCandidateQ,double blockedAppliedQ,long blockedFixedIncrement) {
        public BeforeImage(String id,String directApplyId,SparseStateKey source,
                SparseStateKey destinationBefore,WorldReceiptJournal.State sourceState,
                WorldReceiptJournal.State destinationAfter,long sourceRevision,long destinationRevision,
                long createdGameTick,long expiresGameTick,long transformEpoch,double reservedQ) {
            this(id,directApplyId,source,destinationBefore,sourceState,destinationAfter,sourceRevision,
                    destinationRevision,createdGameTick,expiresGameTick,transformEpoch,reservedQ,
                    null,"",(String)null,0,0,0);
        }
        public BeforeImage(String id,String directApplyId,SparseStateKey source,
                SparseStateKey destinationBefore,WorldReceiptJournal.State sourceState,
                WorldReceiptJournal.State destinationAfter,long sourceRevision,long destinationRevision,
                long createdGameTick,long expiresGameTick,long transformEpoch,double reservedQ,
                MaterialProperties material,String materialRevision,byte[] bePayload,
                double blockedCandidateQ,double blockedAppliedQ,long blockedFixedIncrement) {
            this(id,directApplyId,source,destinationBefore,sourceState,destinationAfter,sourceRevision,
                    destinationRevision,createdGameTick,expiresGameTick,transformEpoch,reservedQ,
                    material,materialRevision,bePayload==null?null:Base64.getEncoder().encodeToString(bePayload),
                    blockedCandidateQ,blockedAppliedQ,blockedFixedIncrement);
        }
        public byte[] bePayload(){return encodedBePayload==null?null:Base64.getDecoder().decode(encodedBePayload);}
        public String blockedApplyId(){return id+"/blocked";}
        /** A delayed planner may never extend the original event's deadline. */
        public BeforeImage withExpiry(long deadline){
            return deadline>=expiresGameTick?this:new BeforeImage(id,directApplyId,source,destinationBefore,
                    sourceState,destinationAfter,sourceRevision,destinationRevision,createdGameTick,deadline,
                    transformEpoch,reservedQ,material,materialRevision,encodedBePayload,
                    blockedCandidateQ,blockedAppliedQ,blockedFixedIncrement);
        }
        public BeforeImage {
            Objects.requireNonNull(id);Objects.requireNonNull(directApplyId);Objects.requireNonNull(source);
            Objects.requireNonNull(destinationBefore);Objects.requireNonNull(destinationAfter);
            SparseStateKey after=destinationAfter.key();
            long dx=Math.abs((long)source.x()-after.x()),dy=Math.abs((long)source.y()-after.y()),dz=Math.abs((long)source.z()-after.z());
            if(id.isBlank()||id.length()>1024||directApplyId.isBlank()||directApplyId.length()>1024
                    ||id.equals(directApplyId)||directApplyId.equals(id+"/blocked")
                    ||!"WORLD".equals(source.ownerSpace())||!sameOwner(source,after)
                    ||!sameCell(destinationBefore,after)||!source.fingerprint().equals(after.fingerprint())
                    ||dx>1||dy>1||dz>1||dx+dy+dz<1||dx+dy+dz>2
                    ||sourceState!=null&&!sourceState.key().equals(source)
                    ||sourceRevision<0||sourceRevision==Long.MAX_VALUE||destinationRevision<0||destinationRevision==Long.MAX_VALUE
                    ||createdGameTick<0||expiresGameTick<createdGameTick||transformEpoch<0
                    ||!Double.isFinite(reservedQ)||reservedQ<0)
                throw new IllegalArgumentException("invalid two-cell move before-image");
            if(destinationAfter.damage()!=(sourceState==null?0:sourceState.damage())
                    ||destinationAfter.remainder()!=(sourceState==null?0:sourceState.remainder())
                    ||destinationAfter.deformation()!=Math.min(255,(sourceState==null?0:sourceState.deformation())+1)
                    ||sourceState!=null&&sourceState.materialGeneration()!=destinationAfter.materialGeneration())
                throw new IllegalArgumentException("move must transfer original damage and increment history once");
            Objects.requireNonNull(materialRevision);
            if(material==null) {
                if(!materialRevision.isEmpty()||encodedBePayload!=null||blockedCandidateQ!=0||blockedAppliedQ!=0||blockedFixedIncrement!=0)
                    throw new IllegalArgumentException("legacy move has no additional damage material");
            } else if(id.length()+"/blocked".length()>1024||materialRevision.length()>1024
                    ||!Double.isFinite(blockedCandidateQ)||blockedCandidateQ<0
                    ||!Double.isFinite(blockedAppliedQ)||blockedAppliedQ<0
                    ||blockedAppliedQ!=Math.min(blockedCandidateQ,reservedQ)
                    ||blockedFixedIncrement<0||blockedFixedIncrement>BlockDamageState.MAX_FIXED_VALUE
                    ||blockedAppliedQ==0&&blockedFixedIncrement!=0
                    ||encodedBePayload!=null&&(encodedBePayload.length()>10924||Base64.getDecoder().decode(encodedBePayload).length>8192))
                throw new IllegalArgumentException("invalid sealed blocked-damage reservation");
        }
    }
    public record Entry(BeforeImage before,Phase phase,String reason) {
        public Entry {Objects.requireNonNull(before);Objects.requireNonNull(phase);
            if(reason==null||reason.isBlank()||reason.length()>512)throw new IllegalArgumentException("move reason");}
    }
    private final Path root;
    private final Map<String,Entry> entries=new LinkedHashMap<>();
    private long bytes;
    private boolean fault;
    public MoveTransactionJournal(Path directory) throws IOException {
        root=directory.toAbsolutePath().normalize();Files.createDirectories(root);
        List<Path> files;
        try(var stream=Files.list(root)) {
            files=stream.filter(p->p.getFileName().toString().endsWith(".pmv"))
                    .limit(MAX_RECORDS+1L).sorted().toList();
        }
        if(files.size()>MAX_RECORDS)throw new IOException("move journal count cap");
        for(Path file:files) {
            if(Files.isSymbolicLink(file)||!Files.isRegularFile(file))throw new IOException("unsafe move record path");
            long size=Files.size(file);
            if(size<20||size>MAX_RECORD_BYTES||bytes+size>MAX_STORE_BYTES)throw new IOException("move journal size cap");
            Entry entry=decode(Files.readAllBytes(file));
            if(!file.equals(path(entry.before.id()))||entries.putIfAbsent(entry.before.id(),entry)!=null)
                throw new IOException("move record identity collision");
            bytes+=size;
        }
    }
    public synchronized Entry get(String id){return entries.get(id);}
    public synchronized List<Entry> snapshot(){return List.copyOf(entries.values());}
    public synchronized boolean faulted(){return fault;}
    /** Duplicate preparation must be exactly the same payload and never authorizes a second mutation. */
    public synchronized boolean prepare(BeforeImage before) throws IOException {
        Entry prior=entries.get(before.id());
        if(prior!=null) {
            if(!prior.before.equals(before))throw new IOException("move id aliases different before-image");
            return false;
        }
        if(entries.size()>=MAX_RECORDS)throw new IOException("move journal full");
        save(new Entry(before,Phase.PREPARED,"TWO_CELL_BEFORE_IMAGE_DURABLE"));return true;
    }
    /** A proven obstruction seals settlement exactly once; it never permits moving the block. */
    public synchronized void block(BeforeImage before) throws IOException {
        Entry prior=entries.get(before.id());
        if(before.material()==null)throw new IOException("missing blocked-damage reservation");
        if(prior!=null) {
            if(!prior.before.equals(before))throw new IOException("move id aliases different before-image");
            if(prior.phase==Phase.BLOCKED_PENDING)return;
            if(prior.phase!=Phase.PREPARED)throw new IOException("terminal move cannot be changed");
        } else if(entries.size()>=MAX_RECORDS)throw new IOException("move journal full");
        save(new Entry(before,Phase.BLOCKED_PENDING,"BLOCKED_RESERVE_DURABLE"));
    }
    public synchronized void finish(String id,Phase phase,String reason) throws IOException {
        Entry prior=entries.get(id);
        if(prior==null||phase==Phase.PREPARED||phase==Phase.BLOCKED_PENDING)throw new IOException("invalid move transition");
        Entry next=new Entry(prior.before,phase,reason);
        if(prior.equals(next))return;
        if(prior.phase!=Phase.PREPARED&&!(prior.phase==Phase.BLOCKED_PENDING&&phase!=Phase.APPLIED))
            throw new IOException("terminal move cannot be changed");
        save(next);
    }
    private void save(Entry entry) throws IOException {
        if(fault)throw new IOException("move journal requires restart after I/O uncertainty");
        byte[] encoded=encode(entry);Path target=path(entry.before.id());
        long prior=Files.exists(target)?Files.size(target):0;
        if(bytes-prior+encoded.length>MAX_STORE_BYTES)throw new IOException("move journal byte cap");
        Path temporary=root.resolve(target.getFileName()+".tmp");
        try {
            if(Files.isSymbolicLink(temporary)||Files.isSymbolicLink(target))throw new IOException("unsafe move journal path");
            try(var out=FileChannel.open(temporary,CREATE,TRUNCATE_EXISTING,WRITE)) {
                ByteBuffer buffer=ByteBuffer.wrap(encoded);while(buffer.hasRemaining())out.write(buffer);out.force(true);
            }
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            try(var directory=FileChannel.open(root,READ)){directory.force(true);}
            catch(AccessDeniedException|UnsupportedOperationException unsupported){/* file is still fsynced */}
            bytes=bytes-prior+encoded.length;entries.put(entry.before.id(),entry);
        } catch(IOException|RuntimeException failure) {
            fault=true;throw new IOException("move journal durable write uncertain",failure);
        }
    }
    private Path path(String id) {
        try{return root.resolve(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(id.getBytes(StandardCharsets.UTF_8)))+".pmv");}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static boolean sameOwner(SparseStateKey a,SparseStateKey b) {
        return a.dimension().equals(b.dimension())&&a.ownerSpace().equals(b.ownerSpace())
                &&a.ownerId().equals(b.ownerId())&&a.ownerGeneration()==b.ownerGeneration();
    }
    private static boolean sameCell(SparseStateKey a,SparseStateKey b) {
        return sameOwner(a,b)&&a.x()==b.x()&&a.y()==b.y()&&a.z()==b.z();
    }
    private static byte[] encode(Entry entry) throws IOException {
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)) {
            var b=entry.before;out.writeUTF(b.id);out.writeUTF(b.directApplyId);key(out,b.source);key(out,b.destinationBefore);
            out.writeBoolean(b.sourceState!=null);if(b.sourceState!=null)state(out,b.sourceState);
            state(out,b.destinationAfter);out.writeLong(b.sourceRevision);out.writeLong(b.destinationRevision);
            out.writeLong(b.createdGameTick);out.writeLong(b.expiresGameTick);out.writeLong(b.transformEpoch);out.writeDouble(b.reservedQ);
            out.writeByte(entry.phase.ordinal());out.writeUTF(entry.reason);
            out.writeBoolean(b.material!=null);
            if(b.material!=null) {
                var m=b.material;
                out.writeByte(m.hardness());out.writeShort(m.threshold());out.writeByte(m.ductilityPercent());
                out.writeByte(m.elasticityPercent());out.writeBoolean(m.unbreakable());out.writeDouble(m.yieldThreshold());
                out.writeUTF(b.materialRevision);byte[] payload=b.bePayload();
                out.writeInt(payload==null?-1:payload.length);if(payload!=null)out.write(payload);
                out.writeDouble(b.blockedCandidateQ);out.writeDouble(b.blockedAppliedQ);out.writeLong(b.blockedFixedIncrement);
            }
        }
        byte[] body=bytes.toByteArray();
        if(body.length+16>MAX_RECORD_BYTES)throw new IOException("move record too large");
        var crc=new CRC32C();crc.update(body);
        return ByteBuffer.allocate(body.length+16).putInt(MAGIC).putInt(SCHEMA).putInt(body.length)
                .putInt((int)crc.getValue()).put(body).array();
    }
    private static Entry decode(byte[] data) throws IOException {
        try(var in=new DataInputStream(new ByteArrayInputStream(data))) {
            if(in.readInt()!=MAGIC)throw new IOException("unknown move schema");
            int schema=in.readInt();
            if(schema!=1&&schema!=SCHEMA)throw new IOException("unknown move schema");
            int length=in.readInt(),checksum=in.readInt();
            if(length!=data.length-16)throw new IOException("move record length mismatch");
            byte[] body=in.readNBytes(length);var crc=new CRC32C();crc.update(body);
            if((int)crc.getValue()!=checksum)throw new IOException("move record checksum mismatch");
            try(var r=new DataInputStream(new ByteArrayInputStream(body))) {
                var before=new BeforeImage(r.readUTF(),r.readUTF(),key(r),key(r),r.readBoolean()?state(r):null,
                        state(r),r.readLong(),r.readLong(),r.readLong(),r.readLong(),r.readLong(),r.readDouble());
                int phase=r.readUnsignedByte();if(phase>=Phase.values().length||schema==1&&phase>=4)throw new IOException("move phase");
                String reason=r.readUTF();
                if(schema==2&&r.readBoolean()){
                    var material=new MaterialProperties(r.readUnsignedByte(),r.readUnsignedShort(),r.readUnsignedByte(),
                            r.readUnsignedByte(),r.readBoolean(),r.readDouble());
                    String revision=r.readUTF();int size=r.readInt();
                    if(size < -1 || size>8192)throw new IOException("BE payload size");
                    byte[] payload=size<0?null:r.readNBytes(size);
                    if(payload!=null&&payload.length!=size)throw new EOFException("BE payload truncated");
                    before=new BeforeImage(before.id(),before.directApplyId(),before.source(),before.destinationBefore(),
                            before.sourceState(),before.destinationAfter(),before.sourceRevision(),before.destinationRevision(),
                            before.createdGameTick(),before.expiresGameTick(),before.transformEpoch(),before.reservedQ(),
                            material,revision,payload,r.readDouble(),r.readDouble(),r.readLong());
                }
                if(Phase.values()[phase]==Phase.BLOCKED_PENDING&&before.material()==null)throw new IOException("unsealed blocked reservation");
                var result=new Entry(before,Phase.values()[phase],reason);
                if(r.available()!=0)throw new IOException("trailing move data");return result;
            }
        } catch(IllegalArgumentException failure){throw new IOException("invalid move data",failure);}
    }
    private static void key(DataOutputStream out,SparseStateKey k) throws IOException {
        out.writeUTF(k.dimension());out.writeUTF(k.ownerSpace());out.writeUTF(k.ownerId());out.writeLong(k.ownerGeneration());
        out.writeInt(k.x());out.writeInt(k.y());out.writeInt(k.z());out.writeUTF(k.fingerprint());
    }
    private static SparseStateKey key(DataInputStream in) throws IOException {
        return new SparseStateKey(in.readUTF(),in.readUTF(),in.readUTF(),in.readLong(),in.readInt(),in.readInt(),in.readInt(),in.readUTF());
    }
    private static void state(DataOutputStream out,WorldReceiptJournal.State s) throws IOException {
        key(out,s.key());out.writeUTF(s.incarnation());out.writeShort(s.damage());out.writeByte(s.remainder());
        out.writeLong(s.materialGeneration());out.writeLong(s.tick());out.writeByte(s.deformation());
    }
    private static WorldReceiptJournal.State state(DataInputStream in) throws IOException {
        return new WorldReceiptJournal.State(key(in),in.readUTF(),in.readUnsignedShort(),in.readUnsignedByte(),
                in.readLong(),in.readLong(),in.readUnsignedByte());
    }
}
