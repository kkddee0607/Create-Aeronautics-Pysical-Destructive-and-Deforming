package com.physicaldestroydeform.world;

import com.physicaldestroydeform.state.SparseStateKey;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.CRC32;
import static java.nio.file.StandardOpenOption.*;

/**
 * PDD 0.5.0 durable World storage. Receipt transitions and damage are fsynced in
 * the SAME recovery-WAL frame. Immutable chunk and receipt-bucket snapshots are
 * checkpointed in bounded batches; an atomic manifest switches WAL generations.
 * A missing or corrupt committed object fails closed, never silently resets damage.
 * No Plot mutation, forced chunk load, or Minecraft API occurs in this class.
 */
public final class WorldPersistentJournal implements WorldJournal {
    public static final int SCHEMA=2, MAX_STATES_PER_CHUNK=4096;
    public static final int MAX_TOTAL_STATES=2_000_000, MAX_RECEIPTS=2_000_000;
    public static final int MAX_REVISIONS_PER_CHUNK=131_072, MAX_TOTAL_REVISIONS=4_000_000;
    public static final int SAVE_DIRTY_BATCH=128;
    public static final long MAX_WAL_BYTES=96L*1024*1024, CHECKPOINT_WAL_BYTES=16L*1024*1024;
    private static final int DISK_MAGIC=0x50445035, WAL_MAGIC=0x50445735;
    private static final int MANIFEST=1, CHUNK=2, RECEIPTS=3;
    private static final byte TRANSITION=1, CLEAR=2, REPLACEMENT=3;
    private static final int MAX_FRAME_BYTES=16*1024, MAX_BLOB_BYTES=64*1024*1024;

    public record ChunkKey(String dimension,int x,int z) implements Comparable<ChunkKey> {
        public ChunkKey {if(dimension==null||dimension.isBlank())throw new IllegalArgumentException("dimension");}
        @Override public int compareTo(ChunkKey b){int v=dimension.compareTo(b.dimension);
            return v!=0?v:(x!=b.x?Integer.compare(x,b.x):Integer.compare(z,b.z));}
    }
    private record Cell(String dimension,int x,int y,int z) implements Comparable<Cell> {
        Cell(SparseStateKey key){this(key.dimension(),key.x(),key.y(),key.z());}
        ChunkKey chunk(){return new ChunkKey(dimension,x>>4,z>>4);}
        @Override public int compareTo(Cell b){int v=dimension.compareTo(b.dimension);
            if(v!=0)return v;v=Integer.compare(x,b.x);if(v!=0)return v;
            v=Integer.compare(y,b.y);return v!=0?v:Integer.compare(z,b.z);}
    }
    private final Path root,manifestPath,legacyPath;
    private final Map<SparseStateKey,WorldReceiptJournal.State> states=new HashMap<>();
    private final Map<String,WorldReceiptJournal.Receipt> receipts=new HashMap<>();
    private final Map<Integer,Set<String>> receiptBucketIndex=new HashMap<>();
    private final Map<Cell,Set<SparseStateKey>> positionKeys=new HashMap<>();
    private final Map<ChunkKey,Set<SparseStateKey>> chunkKeys=new HashMap<>();
    private final Map<Cell,Long> revisions=new HashMap<>();
    private final Map<ChunkKey,Set<Cell>> revisionChunks=new HashMap<>();
    private final Set<String> recoveredUncertain=new HashSet<>();
    private final Set<ChunkKey> dirtyChunks=new TreeSet<>();
    private final Set<Integer> dirtyBuckets=new TreeSet<>();
    private final Map<ChunkKey,String> currentChunks=new HashMap<>();
    private final Map<Integer,String> currentBuckets=new HashMap<>();
    private Map<ChunkKey,String> stagedChunks;
    private Map<Integer,String> stagedBuckets;
    private long generation,walBytes,repairedTailBytes;
    private String legacyHash="";
    private boolean staging, migrated;
    // The committed manifest selects the reader schema; new writes always use SCHEMA=2.
    private int loadingSchema=SCHEMA;

    /** Existing schema-2 WAL stays untouched as an audit and downgrade safety marker. */
    public WorldPersistentJournal(Path directory,Path oldWal) throws IOException {
        root=Objects.requireNonNull(directory).toAbsolutePath().normalize();
        legacyPath=Objects.requireNonNull(oldWal).toAbsolutePath().normalize();
        manifestPath=root.resolve("checkpoint.manifest");
        Files.createDirectories(root);
        if(Files.exists(manifestPath)){
            loadingSchema=readDiskSchema(manifestPath);
            restore();
            if(loadingSchema==1)upgradeSchema1();
        }else bootstrap();
        for(var receipt:receipts.values())if(receipt.status()==WorldReceiptJournal.Status.DEFERRED
                || receipt.status()==WorldReceiptJournal.Status.PREPARED)
            recoveredUncertain.add(receipt.id());
    }
    public synchronized boolean migrated(){return migrated;}
    /** Recovered crash-time incomplete WAL bytes, excluding complete CRC-invalid frames. */
    public synchronized long repairedTailBytes(){return repairedTailBytes;}
    public synchronized StateStats stats(){return new StateStats(generation,states.size(),receipts.size(),
            chunkKeys.size(),revisions.size(),dirtyChunks.size(),dirtyBuckets.size(),walBytes,staging,migrated);}
    public record StateStats(long generation,int states,int receipts,int occupiedChunks,int revisions,
                             int dirtyChunks,int dirtyReceiptBuckets,long walBytes,boolean checkpointing,
                             boolean migrated) { }
    @Override public synchronized WorldReceiptJournal.State get(SparseStateKey key){return states.get(key);}
    @Override public synchronized WorldReceiptJournal.Receipt receipt(String id){return receipts.get(id);}
    @Override public synchronized int size(){return states.size();}
    @Override public synchronized int receiptCount(){return receipts.size();}
    @Override public synchronized long bytes(){return walBytes;}
    @Override public synchronized boolean recoveredUncertain(String id){return recoveredUncertain.contains(id);}
    @Override public synchronized String admissionRejectReason(){
        if(receipts.size()>=MAX_RECEIPTS)return "RECEIPT_LEDGER_CAP_EXCEEDED";
        if(walBytes>=MAX_WAL_BYTES-1024L*1024)return "RECOVERY_WAL_NEAR_CAP";
        return null;
    }
    @Override public synchronized boolean canStore(SparseStateKey key){
        return states.containsKey(key) || (states.size()<MAX_TOTAL_STATES
            && chunkKeys.getOrDefault(chunk(key),Set.of()).size()<MAX_STATES_PER_CHUNK);
    }
    @Override public synchronized long revision(String dimension,int x,int y,int z){
        return revisions.getOrDefault(new Cell(dimension,x,y,z),0L);
    }
    @Override public synchronized boolean hasPosition(String dimension,int x,int y,int z){
        return positionKeys.containsKey(new Cell(dimension,x,y,z));
    }
    @Override public synchronized List<WorldReceiptJournal.State> stateSnapshot(){
        return List.copyOf(states.values());
    }
    @Override public synchronized List<WorldReceiptJournal.Receipt> uncertain(){
        return receipts.values().stream().filter(r->r.status()==WorldReceiptJournal.Status.PREPARED
                ||r.status()==WorldReceiptJournal.Status.DEFERRED).toList();
    }
    /** Positions with durable replacement history, including currently undamaged blocks. */
    public synchronized boolean trackedPosition(String dimension,int x,int y,int z){
        Cell cell=new Cell(dimension,x,y,z);
        return positionKeys.containsKey(cell)||revisions.containsKey(cell);
    }
    public synchronized Set<String> retainedDimensions(){
        var result=new TreeSet<String>();for(var key:currentChunks.keySet())result.add(key.dimension());
        return Set.copyOf(result);
    }
    @Override public synchronized String storageStatus(){
        return "schema="+SCHEMA+" generation="+generation+" chunks="+chunkKeys.size()
                +" states="+states.size()+" revisions="+revisions.size()+" receipts="+receipts.size()
                +" walBytes="+walBytes+" repairedTailBytes="+repairedTailBytes
                +" dirtyChunks="+dirtyChunks.size()
                +" dirtyReceipts="+dirtyBuckets.size()+" checkpointing="+staging;
    }
    @Override public synchronized void transition(WorldReceiptJournal.Receipt r,
            WorldReceiptJournal.State next) throws IOException {
        Objects.requireNonNull(r);
        validateTransition(r,next);
        byte[] frame=frame(out->{out.writeByte(TRANSITION);writeReceipt(out,r);
            out.writeBoolean(next!=null);if(next!=null)writeState(out,next);});
        append(frame); // receipt+state become durable atomically BEFORE publishing memory state
        applyTransition(r,next);
    }
    @Override public synchronized void discardAtPosition(String dim,int x,int y,int z) throws IOException {
        Cell cell=new Cell(dim,x,y,z);
        if(!positionKeys.containsKey(cell))return;
        append(frame(out->{out.writeByte(CLEAR);writeCell(out,cell);}));
        clear(cell);
    }
    @Override public synchronized void discardStale(SparseStateKey key) throws IOException {
        if(states.containsKey(key))discardAtPosition(key.dimension(),key.x(),key.y(),key.z());
    }
    /** Same-state re-placement also advances a durable incarnation generation. */
    @Override public synchronized long replaceBlock(String dim,int x,int y,int z) throws IOException {
        Cell cell=new Cell(dim,x,y,z);
        long previous=revisions.getOrDefault(cell,0L);
        if(previous==Long.MAX_VALUE)throw new IOException("incarnation revision overflow");
        if(previous==0 && (revisions.size()>=MAX_TOTAL_REVISIONS
                ||revisionChunks.getOrDefault(cell.chunk(),Set.of()).size()>=MAX_REVISIONS_PER_CHUNK))
            throw new IOException("INCARNATION_GENERATION_CAP_EXCEEDED");
        long next=previous+1;
        append(frame(out->{out.writeByte(REPLACEMENT);writeCell(out,cell);out.writeLong(next);}));
        clear(cell);setRevision(cell,next);
        return next;
    }
    /** Complete a bounded amount of snapshot work on the server thread. */
    @Override public synchronized int checkpointBatch(int maxObjects) throws IOException {
        if(maxObjects<1)throw new IllegalArgumentException("checkpoint budget must be positive");
        if(!staging){
            if(dirtyChunks.isEmpty()&&dirtyBuckets.isEmpty())return 0;
            staging=true;
            stagedChunks=new HashMap<>(currentChunks);
            stagedBuckets=new HashMap<>(currentBuckets);
        }
        int done=0;long nextGeneration=generation+1;
        final long softTimeBudgetNanos=2_000_000L;
        final long startedAt=System.nanoTime();
        while(done<maxObjects && (!dirtyChunks.isEmpty()||!dirtyBuckets.isEmpty())){
            if(done>0 && System.nanoTime()-startedAt>=softTimeBudgetNanos)break;
            if(!dirtyChunks.isEmpty()){
                ChunkKey key=dirtyChunks.iterator().next();
                String relative="chunks/"+sha256(key.dimension()).substring(0,32)+"/"
                        +key.x()+"_"+key.z()+"."+nextGeneration+".pds";
                // No live damage and no incarnation history: omit the empty shard.
                if(chunkKeys.getOrDefault(key,Set.of()).isEmpty()
                        && revisionChunks.getOrDefault(key,Set.of()).isEmpty()){
                    stagedChunks.remove(key);
                }else{
                    byte[] snapshot=chunkBytes(key);
                    writeDurable(root.resolve(relative),blob(CHUNK,snapshot));
                    stagedChunks.put(key,relative);
                }
                dirtyChunks.remove(key);
            }else{
                int bucket=dirtyBuckets.iterator().next();
                String relative="receipts/"+bucket+"."+nextGeneration+".pdr";
                writeDurable(root.resolve(relative),blob(RECEIPTS,receiptBytes(bucket)));
                stagedBuckets.put(bucket,relative);dirtyBuckets.remove(bucket);
            }
            done++;
        }
        if(dirtyChunks.isEmpty()&&dirtyBuckets.isEmpty())publish();
        return done;
    }
    @Override public synchronized boolean checkpointPending(){return staging;}
    public synchronized boolean checkpointNeeded(){
        return staging || walBytes>=CHECKPOINT_WAL_BYTES || dirtyChunks.size()+dirtyBuckets.size()>=SAVE_DIRTY_BATCH;
    }

    private static ChunkKey chunk(SparseStateKey key){return new ChunkKey(key.dimension(),key.x()>>4,key.z()>>4);}
    private static int bucket(String id){return Integer.parseInt(sha256(id).substring(0,2),16);}
    private void validateTransition(WorldReceiptJournal.Receipt r,WorldReceiptJournal.State next)throws IOException {
        var prior=receipts.get(r.id());
        if(prior!=null&&!prior.key().equals(r.key()))throw new IllegalArgumentException("ApplyId alias");
        if(prior!=null&&(prior.status()==WorldReceiptJournal.Status.APPLIED
                ||prior.status()==WorldReceiptJournal.Status.ABORTED
                ||prior.status()==WorldReceiptJournal.Status.PARTIAL_QUARANTINED))
            throw new IllegalArgumentException("terminal receipt");
        if(next!=null&&(!r.key().equals(next.key())||r.status()!=WorldReceiptJournal.Status.APPLIED))
            throw new IllegalArgumentException("state/receipt mismatch");
        if(prior==null&&receipts.size()>=MAX_RECEIPTS)throw new IOException("RECEIPT_LEDGER_CAP_EXCEEDED");
        if(next!=null && (next.damage()>0||next.remainder()>0) && !canStore(next.key()))
            throw new IOException("SPARSE_CHUNK_CAP_EXCEEDED");
        if(next!=null && (next.damage()>0||next.remainder()>0)
                && hasPosition(next.key().dimension(),next.key().x(),next.key().y(),next.key().z())
                && !states.containsKey(next.key()))
            throw new IOException("STALE_POSITION_STATE_ALIAS");
    }
    private void applyTransition(WorldReceiptJournal.Receipt r,WorldReceiptJournal.State next){
        receipts.put(r.id(),r);
        int receiptBucket=bucket(r.id());
        receiptBucketIndex.computeIfAbsent(receiptBucket,k->new HashSet<>()).add(r.id());
        dirtyBuckets.add(receiptBucket);
        if(next!=null)putState(next);
    }
    private void putState(WorldReceiptJournal.State value){
        SparseStateKey key=value.key();ChunkKey chunk=chunk(key);
        if(value.damage()==0&&value.remainder()==0){removeState(key);return;}
        states.put(key,value);
        positionKeys.computeIfAbsent(new Cell(key),k->new HashSet<>()).add(key);
        chunkKeys.computeIfAbsent(chunk,k->new HashSet<>()).add(key);
        dirtyChunks.add(chunk);
    }
    private void removeState(SparseStateKey key){
        if(states.remove(key)==null)return;
        Cell cell=new Cell(key);Set<SparseStateKey> atPos=positionKeys.get(cell);
        if(atPos!=null){atPos.remove(key);if(atPos.isEmpty())positionKeys.remove(cell);}
        ChunkKey ch=chunk(key);Set<SparseStateKey> inChunk=chunkKeys.get(ch);
        if(inChunk!=null){inChunk.remove(key);if(inChunk.isEmpty())chunkKeys.remove(ch);}
        dirtyChunks.add(ch);
    }
    private void clear(Cell cell){
        var keys=positionKeys.get(cell);
        if(keys!=null)for(var key:List.copyOf(keys))removeState(key);
    }
    private void setRevision(Cell cell,long next){
        if(next<1)throw new IllegalArgumentException("invalid revision");
        revisions.put(cell,next);revisionChunks.computeIfAbsent(cell.chunk(),ignored->new HashSet<>()).add(cell);
        dirtyChunks.add(cell.chunk());
    }

    /** Re-encode all schema-1 committed snapshots and replayed WAL data without
     * modifying the old generation. A crash before the atomic manifest move
     * replays the untouched previous WAL; after it, schema-2 is self-contained.
     */
    private void upgradeSchema1() throws IOException {
        long next=generation+1;
        Map<ChunkKey,String> newChunks=new HashMap<>();
        Map<Integer,String> newBuckets=new HashMap<>();
        Set<ChunkKey> keys=new TreeSet<>(currentChunks.keySet());
        keys.addAll(chunkKeys.keySet());keys.addAll(revisionChunks.keySet());
        for(ChunkKey key:keys){
            if(chunkKeys.getOrDefault(key,Set.of()).isEmpty()
                    && revisionChunks.getOrDefault(key,Set.of()).isEmpty())continue;
            String rel="chunks/"+sha256(key.dimension()).substring(0,32)+"/"
                    +key.x()+"_"+key.z()+"."+next+".pds";
            writeDurable(root.resolve(rel),blob(CHUNK,chunkBytes(key)));
            newChunks.put(key,rel);
        }
        for(int id:new TreeSet<>(receiptBucketIndex.keySet())){
            String rel="receipts/"+id+"."+next+".pdr";
            writeDurable(root.resolve(rel),blob(RECEIPTS,receiptBytes(id)));
            newBuckets.put(id,rel);
        }
        // The manifest is written last. Do not delete legacy data during migration.
        createWal(next);
        writeManifest(next,newChunks,newBuckets);
        currentChunks.clear();currentChunks.putAll(newChunks);
        currentBuckets.clear();currentBuckets.putAll(newBuckets);
        generation=next;loadingSchema=SCHEMA;migrated=true;
        dirtyChunks.clear();dirtyBuckets.clear();
    }
    private static int readDiskSchema(Path path)throws IOException {
        try(var in=new DataInputStream(Files.newInputStream(path))){
            if(in.readInt()!=DISK_MAGIC)throw new IOException("unknown PDD checkpoint magic");
            int version=in.readInt();
            if(version!=1&&version!=SCHEMA)throw new IOException("unsupported PDD checkpoint schema "+version);
            return version;
        }
    }

    private void bootstrap()throws IOException {
        // Never construct the legacy reader if its WAL is absent: that would create it.
        if(Files.exists(legacyPath)){
            legacyHash=fileSha256(legacyPath);
            var old=new WorldReceiptJournal(legacyPath); // strict schema 2/CRC/cap validation
            for(var state:old.stateSnapshot()){
                if(!canStore(state.key()))throw new IOException("legacy chunk cap exceeded during migration");
                putState(state);
                long revision=parseRevision(state.incarnation());
                if(revision>0)setRevision(new Cell(state.key()),revision);
            }
            for(var r:old.receiptSnapshot()){
                if(receipts.putIfAbsent(r.id(),r)!=null)throw new IOException("legacy duplicate ApplyId");
                int receiptBucket=bucket(r.id());
                receiptBucketIndex.computeIfAbsent(receiptBucket,k->new HashSet<>()).add(r.id());
                dirtyBuckets.add(receiptBucket);
            }
            migrated=true;
        }
        generation=1;
        for(var key:List.copyOf(dirtyChunks)){
            String rel="chunks/"+sha256(key.dimension()).substring(0,32)+"/"
                    +key.x()+"_"+key.z()+".1.pds";
            writeDurable(root.resolve(rel),blob(CHUNK,chunkBytes(key)));
            currentChunks.put(key,rel);
        }
        for(var id:List.copyOf(dirtyBuckets)){
            String rel="receipts/"+id+".1.pdr";
            writeDurable(root.resolve(rel),blob(RECEIPTS,receiptBytes(id)));
            currentBuckets.put(id,rel);
        }
        createWal(1);
        writeManifest(1,currentChunks,currentBuckets);
        dirtyChunks.clear();dirtyBuckets.clear();
    }
    private void restore()throws IOException {
        try(var in=new DataInputStream(new ByteArrayInputStream(readBlob(manifestPath,MANIFEST)))){
            generation=in.readLong();if(generation<1)throw new IOException("invalid checkpoint generation");
            legacyHash=readString(in);
            int chunks=in.readInt();if(chunks<0||chunks>MAX_TOTAL_STATES+1_000_000)throw new IOException("invalid shard count");
            for(int i=0;i<chunks;i++){
                ChunkKey key=new ChunkKey(readString(in),in.readInt(),in.readInt());
                String path=readString(in);
                if(currentChunks.putIfAbsent(key,path)!=null)throw new IOException("duplicate chunk manifest entry");
            }
            int buckets=in.readInt();if(buckets<0||buckets>256)throw new IOException("invalid receipt bucket count");
            for(int i=0;i<buckets;i++){
                int b=in.readInt();String path=readString(in);
                if(b<0||b>255||currentBuckets.putIfAbsent(b,path)!=null)
                    throw new IOException("duplicate/invalid receipt manifest entry");
            }
            if(in.available()!=0)throw new IOException("manifest trailing bytes");
        }catch(IllegalArgumentException failure){throw new IOException("invalid manifest contents",failure);}
        if(!legacyHash.isBlank() && (!Files.isRegularFile(legacyPath)
                || !legacyHash.equals(fileSha256(legacyPath))))
            throw new IOException("legacy schema-2 WAL changed after migration; refusing divergent downgrade");
        if(legacyHash.isBlank() && Files.exists(legacyPath))
            throw new IOException("legacy WAL appeared after migration; refusing downgrade divergence");
        for(var e:new TreeMap<>(currentChunks).entrySet())loadChunk(e.getKey(),e.getValue());
        for(var e:new TreeMap<>(currentBuckets).entrySet())loadReceipts(e.getKey(),e.getValue());
        // Snapshots above were loaded into memory without generating dirty writes.
        dirtyChunks.clear();dirtyBuckets.clear();
        replayWal();
    }
    private void loadChunk(ChunkKey expected,String rel)throws IOException {
        try(var in=new DataInputStream(new ByteArrayInputStream(readBlob(checkedFile(rel),CHUNK)))){
            var actual=new ChunkKey(readString(in),in.readInt(),in.readInt());
            if(!expected.equals(actual))throw new IOException("chunk identity mismatch");
            int count=in.readInt();if(count<0||count>MAX_STATES_PER_CHUNK)throw new IOException("chunk state capacity exceeded");
            for(int i=0;i<count;i++){
                var state=readState(in);
                if(!chunk(state.key()).equals(actual)||states.containsKey(state.key())||!canStore(state.key()))
                    throw new IOException("duplicate/mismatched chunk damage record");
                putState(state);
            }
            int revCount=in.readInt();if(revCount<0||revCount>MAX_REVISIONS_PER_CHUNK)
                throw new IOException("excessive chunk revisions");
            for(int i=0;i<revCount;i++){
                Cell cell=readCell(in);long rev=in.readLong();
                if(!cell.chunk().equals(actual)||rev<1||revisions.containsKey(cell)
                        || revisions.size()>=MAX_TOTAL_REVISIONS)
                    throw new IOException("duplicate/invalid incarnation revision");
                setRevision(cell,rev);
            }
            if(in.available()!=0)throw new IOException("chunk snapshot trailing bytes");
        }catch(IllegalArgumentException failure){throw new IOException("invalid chunk snapshot",failure);}
    }
    private void loadReceipts(int expected,String rel)throws IOException {
        try(var in=new DataInputStream(new ByteArrayInputStream(readBlob(checkedFile(rel),RECEIPTS)))){
            int actual=in.readUnsignedByte();if(actual!=expected)throw new IOException("receipt bucket mismatch");
            int count=in.readInt();if(count<0||count>MAX_RECEIPTS)throw new IOException("invalid receipt count");
            for(int i=0;i<count;i++){
                var r=readReceipt(in);
                if(bucket(r.id())!=expected||receipts.size()>=MAX_RECEIPTS||receipts.putIfAbsent(r.id(),r)!=null)
                    throw new IOException("duplicate/invalid receipt ledger entry");
                receiptBucketIndex.computeIfAbsent(expected,k->new HashSet<>()).add(r.id());
            }
            if(in.available()!=0)throw new IOException("receipt snapshot trailing bytes");
        }catch(IllegalArgumentException failure){throw new IOException("invalid receipt snapshot",failure);}
    }
    private byte[] chunkBytes(ChunkKey key)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)){
            writeString(out,key.dimension());out.writeInt(key.x());out.writeInt(key.z());
            var keys=new TreeSet<>(chunkKeys.getOrDefault(key,Set.of()));
            out.writeInt(keys.size());for(var k:keys)writeState(out,states.get(k));
            var cells=new TreeSet<>(revisionChunks.getOrDefault(key,Set.of()));
            out.writeInt(cells.size());for(var c:cells){writeCell(out,c);out.writeLong(revisions.get(c));}
        }
        if(bytes.size()>MAX_BLOB_BYTES)throw new IOException("chunk snapshot size cap exceeded");
        return bytes.toByteArray();
    }
    private byte[] receiptBytes(int id)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)){
            out.writeByte(id);
            // 256 stable SHA-256 partitions keep per-snapshot I/O bounded.
            var keys=new TreeSet<>(receiptBucketIndex.getOrDefault(id,Set.of()));
            out.writeInt(keys.size());for(var k:keys)writeReceipt(out,receipts.get(k));
        }
        if(bytes.size()>MAX_BLOB_BYTES)throw new IOException("receipt shard size cap exceeded");
        return bytes.toByteArray();
    }
    private void publish()throws IOException {
        long next=generation+1;
        createWal(next); // durable empty WAL BEFORE referencing it in manifest
        writeManifest(next,stagedChunks,stagedBuckets); // atomic publish: previous WAL+objects remain valid until now
        Map<ChunkKey,String> previousChunks=new HashMap<>(currentChunks);
        Map<Integer,String> previousBuckets=new HashMap<>(currentBuckets);
        long oldGeneration=generation;
        currentChunks.clear();currentChunks.putAll(stagedChunks);
        currentBuckets.clear();currentBuckets.putAll(stagedBuckets);
        generation=next;staging=false;stagedChunks=null;stagedBuckets=null;
        // Once manifest is durable, cleanup is not part of the safety boundary.
        for(var e:previousChunks.entrySet())if(!Objects.equals(e.getValue(),currentChunks.get(e.getKey())))
            deleteBestEffort(root.resolve(e.getValue()));
        for(var e:previousBuckets.entrySet())if(!Objects.equals(e.getValue(),currentBuckets.get(e.getKey())))
            deleteBestEffort(root.resolve(e.getValue()));
        deleteBestEffort(walPath(oldGeneration));
    }
    private static void deleteBestEffort(Path file){try{Files.deleteIfExists(file);}catch(IOException ignored){/* orphan is safe */}}
    private void writeManifest(long gen,Map<ChunkKey,String> chunks,Map<Integer,String> buckets)throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(b)){
            out.writeLong(gen);writeString(out,legacyHash);
            out.writeInt(chunks.size());for(var e:new TreeMap<>(chunks).entrySet()){
                writeString(out,e.getKey().dimension());out.writeInt(e.getKey().x());out.writeInt(e.getKey().z());
                writeString(out,e.getValue());
            }
            out.writeInt(buckets.size());for(var e:new TreeMap<>(buckets).entrySet()){
                out.writeInt(e.getKey());writeString(out,e.getValue());
            }
        }
        Path tmp=root.resolve("checkpoint.manifest.tmp");
        writeDurable(tmp,blob(MANIFEST,b.toByteArray()));
        try{Files.move(tmp,manifestPath,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException e){throw new IOException("atomic manifest replace unavailable",e);}
        forceDirectory(root);
    }
    private Path walPath(long gen){return root.resolve("recovery-"+gen+".wal");}
    private void createWal(long gen)throws IOException {
        ByteBuffer b=ByteBuffer.allocate(16).putInt(WAL_MAGIC).putInt(SCHEMA).putLong(gen);b.flip();
        Path path=walPath(gen);Files.createDirectories(path.getParent());
        try(var channel=FileChannel.open(path,CREATE,TRUNCATE_EXISTING,WRITE)){
            while(b.hasRemaining())channel.write(b);channel.force(true);
        }
        forceDirectory(root);walBytes=16;
    }
    private void append(byte[] frame)throws IOException {
        if(walBytes+frame.length>MAX_WAL_BYTES)throw new IOException("RECOVERY_WAL_CAP_EXCEEDED");
        Path path=walPath(generation);
        try(var channel=FileChannel.open(path,APPEND,WRITE)){
            if(channel.size()!=walBytes)throw new IOException("recovery WAL changed externally");
            ByteBuffer b=ByteBuffer.wrap(frame);while(b.hasRemaining())channel.write(b);
            channel.force(true);walBytes+=frame.length;
        }
    }
    private void replayWal()throws IOException {
        Path wal=walPath(generation);
        if(!Files.isRegularFile(wal))throw new IOException("missing committed recovery WAL");
        long length=Files.size(wal);if(length<16||length>MAX_WAL_BYTES)throw new IOException("invalid WAL size");
        long lastValid=16;
        boolean incompleteTail=false;
        try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(wal)))){
            if(in.readInt()!=WAL_MAGIC||in.readInt()!=loadingSchema||in.readLong()!=generation)
                throw new IOException("unknown/stale recovery WAL schema");
            long consumed=16;
            while(consumed<length){
                // A final *incomplete* append has never completed fsync, so no
                // vanilla break can have started on its PREPARED receipt.
                // A complete frame with bad CRC is NOT repairable: fail closed.
                if(length-consumed<8){incompleteTail=true;break;}
                int n=in.readInt(),expectedCrc=in.readInt();consumed+=8;
                if(n<1||n>MAX_FRAME_BYTES)throw new IOException("invalid WAL frame size");
                if(length-consumed<n){incompleteTail=true;break;}
                byte[] data=in.readNBytes(n);consumed+=n;
                if(data.length!=n||crc(data)!=expectedCrc)throw new IOException("recovery WAL CRC failure");
                try(var rec=new DataInputStream(new ByteArrayInputStream(data))){
                    int type=rec.readUnsignedByte();
                    if(type==TRANSITION){
                        var receipt=readReceipt(rec);var state=rec.readBoolean()?readState(rec):null;
                        validateTransition(receipt,state);applyTransition(receipt,state);
                    }else if(type==CLEAR){clear(readCell(rec));}
                    else if(type==REPLACEMENT){
                        Cell c=readCell(rec);long next=rec.readLong();
                        if(next<1||next!=revision(c.dimension,c.x,c.y,c.z)+1)
                            throw new IOException("nonsequential replacement generation");
                        clear(c);setRevision(c,next);
                    }else throw new IOException("unknown WAL record kind");
                    if(rec.available()!=0)throw new IOException("trailing recovery WAL record bytes");
                }
                lastValid=consumed;
            }
        }catch(IllegalArgumentException e){throw new IOException("invalid recovered WAL contents",e);}
        if(incompleteTail){
            try(var file=FileChannel.open(wal,WRITE)){
                if(file.size()!=length)throw new IOException("WAL externally modified during repair");
                file.truncate(lastValid);file.force(true);
            }
            repairedTailBytes=length-lastValid;
        }
        walBytes=incompleteTail?lastValid:length;
    }
    private Path checkedFile(String relative)throws IOException {
        if(relative.isEmpty()||relative.length()>4096||relative.indexOf('\\')>=0)
            throw new IOException("unsafe persisted path");
        Path file=root.resolve(relative).normalize();
        if(!file.startsWith(root)||file.equals(root))throw new IOException("unsafe persisted path");
        return file;
    }
    private static void writeDurable(Path path,byte[] bytes)throws IOException {
        Files.createDirectories(path.getParent());
        try(var ch=FileChannel.open(path,CREATE,TRUNCATE_EXISTING,WRITE)){
            ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining())ch.write(b);ch.force(true);
        }
        forceDirectory(path.getParent());
    }
    private static void forceDirectory(Path directory)throws IOException {
        try(var ch=FileChannel.open(directory,READ)){ch.force(true);}
        catch(AccessDeniedException|UnsupportedOperationException unsupported){
            // Windows cannot open a directory FileChannel; file fsync and atomic move still apply.
        }
    }
    private interface Writer{void write(DataOutputStream out)throws IOException;}
    private static byte[] frame(Writer writer)throws IOException {
        var b=new ByteArrayOutputStream();try(var out=new DataOutputStream(b)){writer.write(out);}
        byte[] body=b.toByteArray();if(body.length>MAX_FRAME_BYTES)throw new IOException("oversize WAL record");
        return ByteBuffer.allocate(8+body.length).putInt(body.length).putInt(crc(body)).put(body).array();
    }
    private static byte[] blob(int type,byte[] body)throws IOException {
        if(body.length>MAX_BLOB_BYTES)throw new IOException("oversize snapshot");
        return ByteBuffer.allocate(20+body.length).putInt(DISK_MAGIC).putInt(SCHEMA)
                .putInt(type).putInt(body.length).putInt(crc(body)).put(body).array();
    }
    private byte[] readBlob(Path file,int type)throws IOException {
        if(!Files.isRegularFile(file))throw new IOException("missing checkpoint file: "+file);
        long length=Files.size(file);if(length<20||length>MAX_BLOB_BYTES+20L)
            throw new IOException("invalid checkpoint file size: "+file);
        try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))){
            if(in.readInt()!=DISK_MAGIC||in.readInt()!=loadingSchema||in.readInt()!=type)
                throw new IOException("unsupported checkpoint schema/type: "+file);
            int count=in.readInt(),checksum=in.readInt();
            if(count<0||count>MAX_BLOB_BYTES||count!=length-20)
                throw new IOException("snapshot truncated or extra bytes: "+file);
            byte[] body=in.readNBytes(count);
            if(body.length!=count||crc(body)!=checksum)throw new IOException("snapshot CRC mismatch: "+file);
            return body;
        }
    }
    private static int crc(byte[] bytes){CRC32 c=new CRC32();c.update(bytes);return(int)c.getValue();}
    private static void writeString(DataOutputStream out,String value)throws IOException {
        if(value==null||value.length()>4096)throw new IOException("oversize persistence string");out.writeUTF(value);
    }
    private static String readString(DataInputStream in)throws IOException {
        String value=in.readUTF();if(value.length()>4096)throw new IOException("oversize persistence string");return value;
    }
    private static void writeCell(DataOutputStream out,Cell c)throws IOException {
        writeString(out,c.dimension());out.writeInt(c.x());out.writeInt(c.y());out.writeInt(c.z());
    }
    private static Cell readCell(DataInputStream in)throws IOException {
        return new Cell(readString(in),in.readInt(),in.readInt(),in.readInt());
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
    private static void writeState(DataOutputStream out,WorldReceiptJournal.State s)throws IOException {
        writeKey(out,s.key());writeString(out,s.incarnation());out.writeShort(s.damage());out.writeByte(s.remainder());
        out.writeLong(s.materialGeneration());out.writeLong(s.tick());
    }
    private WorldReceiptJournal.State readState(DataInputStream in)throws IOException {
        return new WorldReceiptJournal.State(readKey(in),readString(in),
                loadingSchema==1?in.readUnsignedByte():in.readUnsignedShort(),
                in.readUnsignedByte(),in.readLong(),in.readLong());
    }
    private static void writeReceipt(DataOutputStream out,WorldReceiptJournal.Receipt r)throws IOException {
        writeString(out,r.id());writeKey(out,r.key());out.writeByte(r.status().ordinal());
        writeString(out,r.reason());out.writeLong(r.tick());
    }
    private static WorldReceiptJournal.Receipt readReceipt(DataInputStream in)throws IOException {
        String id=readString(in);SparseStateKey key=readKey(in);int ordinal=in.readUnsignedByte();
        if(ordinal>=WorldReceiptJournal.Status.values().length)throw new IOException("unknown receipt status");
        return new WorldReceiptJournal.Receipt(id,key,WorldReceiptJournal.Status.values()[ordinal],readString(in),in.readLong());
    }
    private static long parseRevision(String incarnation){int i=incarnation.indexOf('-');
        try{return i<0?0:Math.max(0,Long.parseLong(incarnation.substring(0,i)));}
        catch(NumberFormatException e){return 0;}}
    private static String sha256(String s){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static String fileSha256(Path path)throws IOException {
        try(var in=Files.newInputStream(path)){
            var digest=MessageDigest.getInstance("SHA-256");
            byte[] buffer=new byte[65536];int n;
            while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);
            return HexFormat.of().formatHex(digest.digest());
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
