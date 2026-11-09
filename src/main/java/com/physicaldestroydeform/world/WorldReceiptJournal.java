package com.physicaldestroydeform.world;

import com.physicaldestroydeform.state.SparseStateKey;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;
import static java.nio.file.StandardOpenOption.*;

/** Bounded, synchronous append-only write-ahead log for 0.4.1 static World records.
 * A PREPARED receipt is fsynced BEFORE Vanilla mutation. A replayed PREPARED
 * is quarantined, never executed again. No Plot state is stored here.
 * 0.5.0 owns sharding/migration; this minimal WAL never silently evicts a receipt.
 */
public final class WorldReceiptJournal implements WorldJournal {
    public static final int VERSION=2, MAX_STATES=200_000, MAX_RECEIPTS=60_000;
    public static final long MAX_BYTES=96L*1024*1024;
    private static final int MAGIC=0x50445732, MAX_RECORD_BYTES=16*1024;
    private static final byte TRANSITION=1,CLEAR_POSITION=2;
    public enum Status { PREPARED, DEFERRED, APPLIED, ABORTED, PARTIAL_QUARANTINED }
    public record State(SparseStateKey key,String incarnation,int damage,int remainder,
                        long materialGeneration,long tick) {
        public State {
            if(key==null||!"WORLD".equals(key.ownerSpace())||incarnation==null
                ||incarnation.isBlank()||incarnation.length()>512||damage<0||damage>1024
                ||remainder<0||remainder>255||materialGeneration<0||tick<0)
                throw new IllegalArgumentException("invalid World damage state");
        }
    }
    public record Receipt(String id,SparseStateKey key,Status status,String reason,long tick) {
        public Receipt {
            if(id==null||id.isBlank()||id.length()>1024||key==null||status==null
                ||!"WORLD".equals(key.ownerSpace())||reason==null||reason.length()>512||tick<0)
                throw new IllegalArgumentException("invalid World ApplyReceipt");
        }
    }
    private record Cell(String dimension,int x,int y,int z) {
        Cell(SparseStateKey key){this(key.dimension(),key.x(),key.y(),key.z());}
    }
    private final Path path;
    private final Map<Cell,Set<SparseStateKey>> stateIndex=new HashMap<>();
    private final Map<SparseStateKey,State> states=new HashMap<>();
    private final Map<String,Receipt> receipts=new LinkedHashMap<>();
    private long sizeBytes;

    public WorldReceiptJournal(Path path) throws IOException {
        this.path=Objects.requireNonNull(path).toAbsolutePath().normalize();
        if(Files.exists(this.path))load();
        else {
            Path parent=this.path.getParent();Files.createDirectories(parent);
            // Truncation is never allowed for an already-existing journal.
            try(FileChannel ch=FileChannel.open(this.path,CREATE_NEW,WRITE)) {
                ByteBuffer h=ByteBuffer.allocate(8).putInt(MAGIC).putInt(VERSION);h.flip();
                while(h.hasRemaining())ch.write(h);ch.force(true);
            }
            sizeBytes=8;
        }
    }
    public synchronized State get(SparseStateKey key){return states.get(key);}
    public synchronized Receipt receipt(String id){return receipts.get(id);}
    public synchronized int size(){return states.size();}
    public synchronized int receiptCount(){return receipts.size();}
    public synchronized long bytes(){return sizeBytes;}
    public synchronized List<Receipt> uncertain(){return receipts.values().stream()
            .filter(r->r.status()==Status.PREPARED||r.status()==Status.DEFERRED).toList();}
    public synchronized List<State> stateSnapshot(){return List.copyOf(states.values());}
    /** Complete legacy receipt enumeration for verified schema-2 migration. */
    public synchronized List<Receipt> receiptSnapshot(){return List.copyOf(receipts.values());}
    public synchronized boolean hasPosition(String dimension,int x,int y,int z) {
        return stateIndex.containsKey(new Cell(dimension,x,y,z));
    }
    private void putState(State value) {
        if(value.damage()==0&&value.remainder()==0){removeState(value.key());return;}
        states.put(value.key(),value);
        stateIndex.computeIfAbsent(new Cell(value.key()),ignored->new HashSet<>()).add(value.key());
    }
    private void removeState(SparseStateKey key) {
        if(states.remove(key)==null)return;
        Cell cell=new Cell(key);
        Set<SparseStateKey> associated=stateIndex.get(cell);
        if(associated!=null && associated.remove(key) && associated.isEmpty())stateIndex.remove(cell);
    }

    /** Atomic receipt+state WAL frame: never expose an APPLIED receipt without its state. */
    public synchronized void transition(Receipt r,State next) throws IOException {
        Receipt prior=receipts.get(r.id());
        if(prior!=null&&!prior.key().equals(r.key()))throw new IllegalArgumentException("ApplyId alias");
        if(prior!=null&&(prior.status==Status.APPLIED||prior.status==Status.ABORTED
                ||prior.status==Status.PARTIAL_QUARANTINED))throw new IllegalArgumentException("terminal receipt");
        if(next!=null&&(!r.key().equals(next.key())||r.status()!=Status.APPLIED))
            throw new IllegalArgumentException("state/receipt mismatch");
        boolean newReceipt=prior==null;
        boolean newState=next!=null&&!states.containsKey(next.key())&&(next.damage()>0||next.remainder()>0);
        if(newReceipt&&receipts.size()>=MAX_RECEIPTS || newState&&states.size()>=MAX_STATES)
            throw new IOException("journal capacity reached");
        byte[] record=frame(out->{
            out.writeByte(TRANSITION);writeReceipt(out,r);out.writeBoolean(next!=null);
            if(next!=null)writeState(out,next);
        });
        append(record); // flush WAL before changing in-memory published state
        receipts.put(r.id(),r);
        if(next!=null)putState(next);
    }
    /** State tombstone for any live block replacement, including re-placement of same state. */
    public synchronized void discardAtPosition(String dim,int x,int y,int z) throws IOException {
        boolean found=hasPosition(dim,x,y,z);
        if(!found)return;
        byte[] record=frame(out->{out.writeByte(CLEAR_POSITION);writeString(out,dim);
            out.writeInt(x);out.writeInt(y);out.writeInt(z);});
        append(record);
        Set<SparseStateKey> keys=stateIndex.get(new Cell(dim,x,y,z));
        if(keys!=null)for(SparseStateKey key:List.copyOf(keys))removeState(key);
    }
    public synchronized void discardStale(SparseStateKey key) throws IOException {
        if(states.containsKey(key))discardAtPosition(key.dimension(),key.x(),key.y(),key.z());
    }
    private interface Writer{void write(DataOutputStream out)throws IOException;}
    private static byte[] frame(Writer writer)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)){writer.write(out);}
        byte[] body=bytes.toByteArray();
        if(body.length>MAX_RECORD_BYTES)throw new IOException("oversize WAL record");
        CRC32 checksum=new CRC32();checksum.update(body);
        ByteBuffer framed=ByteBuffer.allocate(8+body.length).putInt(body.length)
                .putInt((int)checksum.getValue()).put(body);
        return framed.array();
    }
    private void append(byte[] bytes)throws IOException {
        if(sizeBytes+bytes.length>MAX_BYTES)throw new IOException("WAL storage byte cap reached");
        // OPEN without CREATE: missing journal is an error, never recreate silently.
        try(FileChannel file=FileChannel.open(path,WRITE,APPEND)) {
            if(file.size()!=sizeBytes)throw new IOException("WAL size changed externally");
            ByteBuffer data=ByteBuffer.wrap(bytes);
            while(data.hasRemaining())file.write(data);
            file.force(true);
            sizeBytes+=bytes.length;
        }
    }
    private void load()throws IOException {
        long length=Files.size(path);
        if(length<8||length>MAX_BYTES)throw new IOException("invalid WAL size");
        Map<SparseStateKey,State> decodedStates=new HashMap<>();
        Map<String,Receipt> decodedReceipts=new LinkedHashMap<>();
        try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if(in.readInt()!=MAGIC||in.readInt()!=VERSION)throw new IOException("unknown WAL schema");
            long consumed=8;
            while(consumed<length) {
                if(length-consumed<8)throw new IOException("truncated WAL frame header");
                int count=in.readInt();int storedCrc=in.readInt();consumed+=8;
                if(count<1||count>MAX_RECORD_BYTES||length-consumed<count)
                    throw new IOException("truncated/oversize WAL record");
                byte[] data=in.readNBytes(count);consumed+=count;
                if(data.length!=count)throw new IOException("unexpected WAL EOF");
                CRC32 crc=new CRC32();crc.update(data);
                if((int)crc.getValue()!=storedCrc)throw new IOException("WAL CRC mismatch");
                try(var record=new DataInputStream(new ByteArrayInputStream(data))){
                    int kind=record.readUnsignedByte();
                    if(kind==TRANSITION) {
                        Receipt r=readReceipt(record);
                        boolean hasState=record.readBoolean();
                        State v=hasState?readState(record):null;
                        if(v!=null&&(r.status()!=Status.APPLIED||!v.key().equals(r.key())))
                            throw new IOException("WAL state/receipt inconsistency");
                        Receipt prior=decodedReceipts.put(r.id(),r);
                        if(prior!=null && (!prior.key().equals(r.key())
                                ||prior.status()==Status.APPLIED||prior.status()==Status.ABORTED
                                ||prior.status()==Status.PARTIAL_QUARANTINED))
                            throw new IOException("duplicate/terminal WAL receipt");
                        if(v!=null) {
                            if(v.damage()==0&&v.remainder()==0)decodedStates.remove(v.key());
                            else decodedStates.put(v.key(),v);
                        }
                    } else if(kind==CLEAR_POSITION) {
                        String dim=readString(record);
                        int x=record.readInt(),y=record.readInt(),z=record.readInt();
                        decodedStates.keySet().removeIf(k->k.dimension().equals(dim)
                                &&k.x()==x&&k.y()==y&&k.z()==z);
                    } else throw new IOException("unknown WAL record kind");
                    if(record.available()!=0)throw new IOException("WAL trailing record bytes");
                }
                if(decodedStates.size()>MAX_STATES||decodedReceipts.size()>MAX_RECEIPTS)
                    throw new IOException("WAL restored capacity exceeded");
            }
        }catch(IllegalArgumentException e){throw new IOException("invalid WAL contents",e);}
        for(var value:decodedStates.values())putState(value);
        receipts.putAll(decodedReceipts);sizeBytes=length;
    }
    private static void writeState(DataOutputStream out,State s)throws IOException {
        writeKey(out,s.key());writeString(out,s.incarnation());if(s.damage()>255)throw new IOException("legacy schema-2 WAL cannot write extended damage");
        out.writeByte(s.damage());
        out.writeByte(s.remainder());out.writeLong(s.materialGeneration());out.writeLong(s.tick());
    }
    private static State readState(DataInputStream in)throws IOException {
        return new State(readKey(in),readString(in),in.readUnsignedByte(),in.readUnsignedByte(),in.readLong(),in.readLong());
    }
    private static void writeReceipt(DataOutputStream out,Receipt r)throws IOException {
        writeString(out,r.id());writeKey(out,r.key());out.writeByte(r.status().ordinal());
        writeString(out,r.reason());out.writeLong(r.tick());
    }
    private static Receipt readReceipt(DataInputStream in)throws IOException {
        String id=readString(in);SparseStateKey key=readKey(in);int status=in.readUnsignedByte();
        if(status>=Status.values().length)throw new IOException("unknown status");
        return new Receipt(id,key,Status.values()[status],readString(in),in.readLong());
    }
    private static void writeKey(DataOutputStream out,SparseStateKey key)throws IOException {
        writeString(out,key.dimension());writeString(out,key.ownerSpace());writeString(out,key.ownerId());
        out.writeLong(key.ownerGeneration());out.writeInt(key.x());out.writeInt(key.y());
        out.writeInt(key.z());writeString(out,key.fingerprint());
    }
    private static SparseStateKey readKey(DataInputStream in)throws IOException {
        return new SparseStateKey(readString(in),readString(in),readString(in),in.readLong(),
                in.readInt(),in.readInt(),in.readInt(),readString(in));
    }
    private static void writeString(DataOutputStream out,String value)throws IOException {
        if(value.length()>4096)throw new IOException("oversize WAL string");out.writeUTF(value);
    }
    private static String readString(DataInputStream in)throws IOException {
        String value=in.readUTF();if(value.length()>4096)throw new IOException("oversize WAL string");return value;
    }
}
