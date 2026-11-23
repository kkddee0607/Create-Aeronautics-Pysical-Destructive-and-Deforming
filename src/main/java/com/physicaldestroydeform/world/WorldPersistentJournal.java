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
    public static final int SCHEMA=6, MAX_STATES_PER_CHUNK=4096;
    public static final int MAX_TOTAL_STATES=2_000_000, MAX_RECEIPTS=2_000_000;
    public static final int MAX_REVISIONS_PER_CHUNK=131_072, MAX_TOTAL_REVISIONS=4_000_000;
    public static final int SAVE_DIRTY_BATCH=128;
    public static final long MAX_WAL_BYTES=96L*1024*1024, CHECKPOINT_WAL_BYTES=16L*1024*1024;
    private static final int DISK_MAGIC=0x50445035, WAL_MAGIC=0x50445735;
    private static final int MANIFEST=1, CHUNK=2, RECEIPTS=3;
    private static final byte TRANSITION=1, CLEAR=2, REPLACEMENT=3, RELOCATION=4, COPYCAT_SHAPE=5;
    private static final int MAX_FRAME_BYTES=16*1024, MAX_BLOB_BYTES=64*1024*1024;
    public static final long RECEIPT_CACHE_BYTES=16L*1024*1024;

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
    /** Compact index: no cold ApplyId Strings, only offsets, record CRCs and sorted
     * SHA-256 candidate keys. A matching candidate always reads the complete ID. */
    private record ReceiptShard(String relative,int schema,int bodyCrc,int[] offsets,int[] crcs,long[] candidates) {
        int count(){return crcs.length;}
    }
    private final Map<Integer,ReceiptShard> receiptShards=new HashMap<>();
    private Map<Integer,ReceiptShard> stagedReceiptShards;
    private final Map<Integer,Map<String,WorldReceiptJournal.Receipt>> receiptOverlay=new HashMap<>();
    private final Map<String,WorldReceiptJournal.Receipt> uncertainReceipts=new HashMap<>();
    private final Map<String,WorldReceiptJournal.Receipt> quarantinedReceipts=new HashMap<>();
    private final LinkedHashMap<String,WorldReceiptJournal.Receipt> receiptCache=new LinkedHashMap<>(16,.75f,true);
    private final long receiptCacheLimit;
    private final int[] receiptBucketCounts=new int[256];
    private int receiptCount,overlayCount;
    private long receiptCacheBytes,receiptCacheHits,receiptDiskReads;
    private IOException receiptReadFault;
    private final Map<Cell,Set<SparseStateKey>> positionKeys=new HashMap<>();
    private final Map<ChunkKey,Set<SparseStateKey>> chunkKeys=new HashMap<>();
    private final Map<Cell,Long> revisions=new HashMap<>();
    private final Map<ChunkKey,Set<Cell>> revisionChunks=new HashMap<>();
    private final Set<String> recoveredUncertain=new HashSet<>();
    private final Map<Cell,Integer> pendingPositions=new HashMap<>();
    private int pendingCount;
    private final Set<ChunkKey> dirtyChunks=new TreeSet<>();
    private final Set<Integer> dirtyBuckets=new TreeSet<>();
    private final Map<ChunkKey,String> currentChunks=new HashMap<>();
    private final Map<Integer,String> currentBuckets=new HashMap<>();
    private Map<ChunkKey,String> stagedChunks;
    private Map<Integer,String> stagedBuckets;
    private long generation,walBytes,repairedTailBytes;
    private String legacyHash="";
    private boolean staging, migrated;
    private boolean appendUncertain;
    // The committed manifest selects the reader schema; new writes use schema 5.
    private int loadingSchema=SCHEMA;

    /** Existing schema-2 WAL stays untouched as an audit and downgrade safety marker. */
    public WorldPersistentJournal(Path directory,Path oldWal) throws IOException {
        this(directory,oldWal,RECEIPT_CACHE_BYTES);
    }
    WorldPersistentJournal(Path directory,Path oldWal,long receiptCacheLimit) throws IOException {
        if(receiptCacheLimit<0||receiptCacheLimit>RECEIPT_CACHE_BYTES)throw new IllegalArgumentException("receipt cache budget");
        this.receiptCacheLimit=receiptCacheLimit;
        root=Objects.requireNonNull(directory).toAbsolutePath().normalize();
        legacyPath=Objects.requireNonNull(oldWal).toAbsolutePath().normalize();
        manifestPath=root.resolve("checkpoint.manifest");
        Files.createDirectories(root);
        if(Files.exists(manifestPath)){
            loadingSchema=readDiskSchema(manifestPath);
            restore();
            if(loadingSchema<SCHEMA)upgradeSchema();
        }else bootstrap();
        recoveredUncertain.addAll(uncertainReceipts.keySet());
    }
    public synchronized boolean migrated(){return migrated;}
    /** Recovered crash-time incomplete WAL bytes, excluding complete CRC-invalid frames. */
    public synchronized long repairedTailBytes(){return repairedTailBytes;}
    public synchronized boolean receiptLookupFaulted(){return receiptReadFault!=null;}
    public synchronized StateStats stats(){return new StateStats(generation,states.size(),receiptCount,
            chunkKeys.size(),revisions.size(),dirtyChunks.size(),dirtyBuckets.size(),walBytes,staging,migrated);}
    public record StateStats(long generation,int states,int receipts,int occupiedChunks,int revisions,
                             int dirtyChunks,int dirtyReceiptBuckets,long walBytes,boolean checkpointing,
                             boolean migrated) { }
    public record ReceiptMemoryStats(int indexedReceipts,int dirtyReceipts,int cachedReceipts,
                                     int uncertainReceipts,int quarantinedReceipts,long cacheBytes,
                                     long cacheHits,long diskReads) { }
    public synchronized ReceiptMemoryStats receiptMemoryStats(){
        return new ReceiptMemoryStats(indexedReceiptCount(),overlayCount,receiptCache.size(),
                uncertainReceipts.size(),quarantinedReceipts.size(),receiptCacheBytes,receiptCacheHits,receiptDiskReads);
    }
    private int indexedReceiptCount(){return receiptShards.values().stream().mapToInt(ReceiptShard::count).sum();}
    private void checkReceiptReadHealth()throws IOException{
        if(receiptReadFault!=null)throw new IOException("RECEIPT_DISK_LOOKUP_FAILED",receiptReadFault);
    }
    /** The complete validated index excludes absent hashes exactly. Hash collisions
     * are resolved through full record reads, never interpreted as receipt identity. */
    private WorldReceiptJournal.Receipt lookupReceipt(String id)throws IOException{
        checkReceiptReadHealth();
        if(id==null)return null;
        var hot=uncertainReceipts.get(id);if(hot!=null)return hot;
        var quarantined=quarantinedReceipts.get(id);if(quarantined!=null)return quarantined;
        var cached=receiptCache.get(id);if(cached!=null){receiptCacheHits++;return cached;}
        long hash=receiptHash(id);int bucket=(int)(hash>>>56);
        var overlay=receiptOverlay.get(bucket);if(overlay!=null){var r=overlay.get(id);if(r!=null)return r;}
        var shard=receiptShards.get(bucket);if(shard==null)return null;
        long candidate=candidateKey(hash,0),prefix=candidate>>32;
        int i=Arrays.binarySearch(shard.candidates(),candidate);if(i<0)i=-i-1;
        if(i==shard.count()||shard.candidates()[i]>>32!=prefix)return null;
        try(var f=new RandomAccessFile(checkedFile(shard.relative()).toFile(),"r")){
            int bodyLength=shard.offsets()[shard.count()];
            if(f.length()!=20L+bodyLength||f.readInt()!=DISK_MAGIC||f.readInt()!=shard.schema()
                    ||f.readInt()!=RECEIPTS||f.readInt()!=bodyLength||f.readInt()!=shard.bodyCrc())
                throw new IOException("changed cold receipt shard header");
            for(;i<shard.count()&&shard.candidates()[i]>>32==prefix;i++){
                int record=(int)shard.candidates()[i];
                int start=shard.offsets()[record],length=shard.offsets()[record+1]-start;
                f.seek(20L+start);byte[] bytes=new byte[length];f.readFully(bytes);receiptDiskReads++;
                if(crc(bytes)!=shard.crcs()[record])throw new IOException("cold receipt record CRC mismatch");
                try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
                    var r=readReceipt(in,shard.schema());
                    if(in.available()!=0)throw new IOException("cold receipt record trailing bytes");
                    if(r.id().equals(id)){cacheCompleted(r);return r;}
                }
            }
            return null;
        }catch(IOException|IllegalArgumentException failure){
            receiptReadFault=failure instanceof IOException io?io:new IOException("invalid cold receipt record",failure);
            throw receiptReadFault;
        }
    }
    // Conservative weighted budget includes key, receipt, map entry and worst-case
    // UTF-16 backing arrays; precise JVM object layout is implementation-dependent.
    private static long receiptWeight(WorldReceiptJournal.Receipt r){
        var k=r.key();return 512L+2L*(r.id().length()+r.reason().length()+k.dimension().length()
                +k.ownerSpace().length()+k.ownerId().length()+k.fingerprint().length());
    }
    private void cacheCompleted(WorldReceiptJournal.Receipt r){
        if(r.status()!=WorldReceiptJournal.Status.APPLIED&&r.status()!=WorldReceiptJournal.Status.ABORTED)return;
        long weight=receiptWeight(r);if(weight>receiptCacheLimit)return;
        while(receiptCacheBytes+weight>receiptCacheLimit){
            var old=receiptCache.pollFirstEntry();receiptCacheBytes-=receiptWeight(old.getValue());
        }
        receiptCache.put(r.id(),r);receiptCacheBytes+=weight;
    }
    @Override public synchronized WorldReceiptJournal.State get(SparseStateKey key){return states.get(key);}
    @Override public synchronized WorldReceiptJournal.Receipt receipt(String id){
        try{return lookupReceipt(id);}
        catch(IOException failure){throw new UncheckedIOException("RECEIPT_DISK_LOOKUP_FAILED",failure);}
    }
    @Override public synchronized int size(){return states.size();}
    @Override public synchronized int receiptCount(){return receiptCount;}
    @Override public synchronized long bytes(){return walBytes;}
    @Override public synchronized boolean recoveredUncertain(String id){return recoveredUncertain.contains(id);}
    @Override public synchronized String admissionRejectReason(){
        if(receiptReadFault!=null)return "RECEIPT_DISK_LOOKUP_FAILED";
        if(receiptCount>=MAX_RECEIPTS)return "RECEIPT_LEDGER_CAP_EXCEEDED";
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
    /** Exact sparse bucket lookup; no global scan during native block movement. */
    public synchronized List<WorldReceiptJournal.State> statesAtPosition(SparseStateKey key){
        var keys=positionKeys.get(new Cell(key));
        return keys==null?List.of():keys.stream().map(states::get).filter(Objects::nonNull).toList();
    }
    @Override public synchronized List<WorldReceiptJournal.Receipt> uncertain(){
        return List.copyOf(uncertainReceipts.values());
    }
    /** Startup-only enumeration; never scan the full receipt ledger each tick. */
    public synchronized List<WorldReceiptJournal.Receipt> quarantinedSnapshot(){
        return List.copyOf(quarantinedReceipts.values());
    }
    /** Earlier moreFix builds misclassified QUEUED DEFERRED as ambiguous mutation.
     * The reason itself names the previous state; no Vanilla break begins before
     * a separately fsynced PREPARED record. Do not rewrite the terminal receipt.
     */
    public static boolean isLegacyUnexecutedDeferred(WorldReceiptJournal.Receipt receipt){
        return receipt.status()==WorldReceiptJournal.Status.PARTIAL_QUARANTINED
                && receipt.reason().startsWith("RESTART_UNCERTAIN_DEFERRED_");
    }
    /** Positions with durable replacement history, including currently undamaged blocks. */
    public synchronized boolean trackedPosition(String dimension,int x,int y,int z){
        Cell cell=new Cell(dimension,x,y,z);
        return positionKeys.containsKey(cell)||revisions.containsKey(cell)||pendingPositions.containsKey(cell);
    }
    @Override public boolean supportsPendingPayload() { return true; }
    @Override public synchronized void enqueue(WorldCommitCore.Intent intent,
            com.physicaldestroydeform.DamageModel.Settings settings,long expiresGameTick)throws IOException {
        var pending=new WorldReceiptJournal.Pending(intent,settings,receiptCount,expiresGameTick);
        transition(new WorldReceiptJournal.Receipt(intent.applyId(),intent.key(),
                WorldReceiptJournal.Status.DEFERRED,"QUEUED_IN_CURRENT_RUN",intent.eventTick(),pending),null);
    }
    /** Clean stop preserves the actual rotated FIFO order in the next atomic checkpoint.
     * On a crash before publication, the previous WAL still holds admission order. */
    public synchronized void stagePendingOrder(List<WorldCommitCore.Intent> queue)throws IOException{
        var ids=new HashSet<String>();
        for(var intent:queue){
            var receipt=lookupReceipt(intent.applyId());
            if(receipt!=null && receipt.pending()!=null){
                if(!receipt.pending().intent().equals(intent)||!ids.add(intent.applyId()))
                    throw new IOException("pending checkpoint identity mismatch");
            }
        }
        if(ids.size()!=pendingCount)throw new IOException("incomplete pending checkpoint");
        long order=0;
        for(var intent:queue){
            var receipt=lookupReceipt(intent.applyId());
            if(receipt==null||receipt.pending()==null)continue;
            var p=receipt.pending();
            long next=order++;
            if(p.order()==next)continue;
            var reordered=new WorldReceiptJournal.Pending(p.intent(),p.settings(),next,p.expiresGameTick());
            var replacement=new WorldReceiptJournal.Receipt(receipt.id(),receipt.key(),
                    receipt.status(),receipt.reason(),receipt.tick(),reordered);
            uncertainReceipts.put(receipt.id(),replacement);
            putOverlay(replacement);
        }
    }
    public synchronized Set<String> retainedDimensions(){
        var result=new TreeSet<String>();for(var key:currentChunks.keySet())result.add(key.dimension());
        return Set.copyOf(result);
    }
    @Override public synchronized String storageStatus(){
        return "schema="+SCHEMA+" generation="+generation+" chunks="+chunkKeys.size()
                +" states="+states.size()+" revisions="+revisions.size()+" receipts="+receiptCount
                +" walBytes="+walBytes+" repairedTailBytes="+repairedTailBytes
                +" dirtyChunks="+dirtyChunks.size()
                +" dirtyReceipts="+dirtyBuckets.size()+" recoveredPending="+recoveredUncertain.size()
                +" checkpointing="+staging+" receiptCacheBytes="+receiptCacheBytes
                +" receiptCacheHits="+receiptCacheHits+" receiptDiskReads="+receiptDiskReads
                +" receiptDirty="+overlayCount+" receiptIndexed="+indexedReceiptCount()
                +" receiptReadFault="+(receiptReadFault!=null);
    }
    @Override public synchronized void transition(WorldReceiptJournal.Receipt r,
            WorldReceiptJournal.State next) throws IOException {
        Objects.requireNonNull(r);
        var previous=lookupReceipt(r.id());
        if(previous!=null&&previous.reason().startsWith("MOVE_PREPARED:")
                &&r.status()==WorldReceiptJournal.Status.APPLIED)
            throw new IOException("MOVE_REQUIRES_TWO_CELL_FINALIZATION");
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
    /**
     * Finalize a proven two-cell move in one fsynced frame. This does NOT move blocks:
     * the caller must already hold a durable before-image, both mutation guards and
     * a verified world/owner post-state. An uncertain PREPARED must not call this.
     */
    public synchronized void finalizeRelocation(WorldReceiptJournal.Receipt receipt,
            WorldReceiptJournal.State expectedSource,WorldReceiptJournal.State destination,
            long sourceRevision,long destinationRevision) throws IOException {
        validateRelocation(receipt,expectedSource,destination,sourceRevision,destinationRevision);
        append(frame(out->{out.writeByte(RELOCATION);writeReceipt(out,receipt);
            out.writeBoolean(expectedSource!=null);if(expectedSource!=null)writeState(out,expectedSource);
            writeState(out,destination);out.writeLong(sourceRevision);out.writeLong(destinationRevision);}));
        applyRelocation(receipt,destination,sourceRevision,destinationRevision);
    }
    /** Must be called before physical setters; finalization rechecks after readback. */
    public synchronized void checkRelocationAdmission(WorldReceiptJournal.Receipt receipt,
            WorldReceiptJournal.State expectedSource,WorldReceiptJournal.State destination,
            long sourceRevision,long destinationRevision) throws IOException {
        validateRelocation(receipt,expectedSource,destination,sourceRevision,destinationRevision);
        byte[] encoded=frame(out->{out.writeByte(RELOCATION);writeReceipt(out,receipt);
            out.writeBoolean(expectedSource!=null);if(expectedSource!=null)writeState(out,expectedSource);
            writeState(out,destination);out.writeLong(sourceRevision);out.writeLong(destinationRevision);});
        if(walBytes+encoded.length>MAX_WAL_BYTES)throw new IOException("MOVE_WAL_CAP");
    }
    /** Binds the PREPARED marker to the exact transfer. The companion move WAL
     * must retain its complete two-cell before-image; this digest is not recovery data. */
    public static String relocationPreparationReason(SparseStateKey source,
            WorldReceiptJournal.State expectedSource,WorldReceiptJournal.State destination,
            long sourceRevision,long destinationRevision) throws IOException {
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)) {
            writeKey(out,source);out.writeBoolean(expectedSource!=null);
            if(expectedSource!=null)writeState(out,expectedSource);
            writeState(out,destination);out.writeLong(sourceRevision);out.writeLong(destinationRevision);
        }
        return "MOVE_PREPARED:"+sha256(HexFormat.of().formatHex(bytes.toByteArray()));
    }
    private void validateRelocation(WorldReceiptJournal.Receipt r,WorldReceiptJournal.State expected,
            WorldReceiptJournal.State next,long fromRevision,long toRevision) throws IOException {
        Objects.requireNonNull(r);Objects.requireNonNull(next);
        var prior=lookupReceipt(r.id());var from=r.key();var to=next.key();
        long dx=Math.abs((long)from.x()-to.x()),dy=Math.abs((long)from.y()-to.y()),dz=Math.abs((long)from.z()-to.z());
        if(prior==null||prior.status()!=WorldReceiptJournal.Status.PREPARED
                ||!prior.reason().equals(relocationPreparationReason(from,expected,next,fromRevision,toRevision))
                ||r.status()!=WorldReceiptJournal.Status.APPLIED||!"MOVE_POSTSTATE_CONFIRMED".equals(r.reason())
                ||!from.dimension().equals(to.dimension())||!from.ownerSpace().equals(to.ownerSpace())
                ||!from.ownerId().equals(to.ownerId())||from.ownerGeneration()!=to.ownerGeneration()
                ||!from.fingerprint().equals(to.fingerprint())||dx>1||dy>1||dz>1||dx+dy+dz<1||dx+dy+dz>2)
            throw new IOException("INVALID_MOVE_RECEIPT_OR_OWNER");
        validateTransition(r,null);
        if(expected!=null&&!expected.key().equals(from))throw new IOException("MOVE_SOURCE_KEY_MISMATCH");
        if(!Objects.equals(states.get(from),expected)
                ||positionKeys.getOrDefault(new Cell(from),Set.of()).size()!=(expected==null?0:1)
                ||hasPosition(to.dimension(),to.x(),to.y(),to.z()))
            throw new IOException("MOVE_SPARSE_CAS_MISMATCH");
        if(next.damage()!=(expected==null?0:expected.damage())
                ||next.remainder()!=(expected==null?0:expected.remainder())
                ||next.deformation()!=Math.min(255,(expected==null?0:expected.deformation())+1)
                ||expected!=null&&next.materialGeneration()!=expected.materialGeneration()
                ||next.tick()!=r.tick())throw new IOException("MOVE_STATE_TRANSFER_MISMATCH");
        Cell a=new Cell(from),b=new Cell(to);
        if(fromRevision<0||toRevision<0||fromRevision==Long.MAX_VALUE||toRevision==Long.MAX_VALUE
                ||revision(a.dimension,a.x,a.y,a.z)!=fromRevision||revision(b.dimension,b.x,b.y,b.z)!=toRevision)
            throw new IOException("MOVE_REVISION_CAS_MISMATCH");
        if(!next.incarnation().startsWith((toRevision+1)+"-")
                ||!com.physicaldestroydeform.plot.PlotKeyCodec.isPlotStorage(to)
                  &&!next.incarnation().equals((toRevision+1)+"-"+sha256(to.fingerprint())))
            throw new IOException("MOVE_DESTINATION_INCARNATION_MISMATCH");
        int newRevisions=(fromRevision==0?1:0)+(toRevision==0?1:0);
        if((long)revisions.size()+newRevisions>MAX_TOTAL_REVISIONS)throw new IOException("MOVE_REVISION_CAP");
        for(ChunkKey c:Set.copyOf(List.of(a.chunk(),b.chunk()))) {
            int added=(fromRevision==0&&a.chunk().equals(c)?1:0)+(toRevision==0&&b.chunk().equals(c)?1:0);
            if(revisionChunks.getOrDefault(c,Set.of()).size()+added>MAX_REVISIONS_PER_CHUNK)
                throw new IOException("MOVE_REVISION_CHUNK_CAP");
        }
        int released=expected==null?0:1;
        if((long)states.size()-released+1>MAX_TOTAL_STATES
                ||chunkKeys.getOrDefault(chunk(to),Set.of()).size()
                    -(expected!=null&&chunk(from).equals(chunk(to))?1:0)+1>MAX_STATES_PER_CHUNK)
            throw new IOException("MOVE_SPARSE_CAP");
    }
    private void applyRelocation(WorldReceiptJournal.Receipt r,WorldReceiptJournal.State next,
            long fromRevision,long toRevision) {
        Cell a=new Cell(r.key()),b=new Cell(next.key());
        clear(a);clear(b);setRevision(a,fromRevision+1);setRevision(b,toRevision+1);
        applyTransition(r,null);putState(next);
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
    /** Called only after native setter proof has been durably sealed in both
     * legs. Empty state alone is never evidence of replacement. */
    synchronized boolean checkLineageSource(LineageJournal.Transfer t)throws IOException {
        var key=t.source().key();var r=receipt(t.outId());
        long rev=revision(key.dimension(),key.x(),key.y(),key.z());
        if(t.fromRevision()<0||t.fromRevision()==Long.MAX_VALUE||r==null||!r.key().equals(key))
            throw new IOException("LINEAGE_SOURCE_RECEIPT_CAS");
        if(rev==t.fromRevision()+1&&!hasPosition(key.dimension(),key.x(),key.y(),key.z()))return false;
        if(r.status()!=WorldReceiptJournal.Status.PREPARED||rev!=t.fromRevision())
            throw new IOException("LINEAGE_SOURCE_REVISION_CAS");
        if(!Objects.equals(get(key),t.source())||statesAtPosition(key).size()!=1)
            throw new IOException("LINEAGE_SOURCE_STATE_CHANGED");
        checkLineageRevisionCapacity(key,rev);
        return true;
    }
    synchronized boolean checkLineageDestination(LineageJournal.Transfer t)throws IOException {
        var key=t.destination();var r=receipt(t.inId());
        long rev=revision(key.dimension(),key.x(),key.y(),key.z());
        if(r==null||!r.key().equals(key)||r.status()!=WorldReceiptJournal.Status.PREPARED
                ||t.toRevision()<0||t.toRevision()==Long.MAX_VALUE)
            throw new IOException("LINEAGE_DESTINATION_RECEIPT_CAS");
        if(hasPosition(key.dimension(),key.x(),key.y(),key.z())||!canStore(key))
            throw new IOException("LINEAGE_DESTINATION_STATE_CAS");
        if(rev==t.toRevision()+1)return false;
        if(rev!=t.toRevision())throw new IOException("LINEAGE_DESTINATION_REVISION_CAS");
        checkLineageRevisionCapacity(key,rev);
        return true;
    }
    private void checkLineageRevisionCapacity(SparseStateKey key,long rev)throws IOException{
        if(rev==0&&(revisions.size()>=MAX_TOTAL_REVISIONS
                ||revisionChunks.getOrDefault(new Cell(key).chunk(),Set.of()).size()>=MAX_REVISIONS_PER_CHUNK))
            throw new IOException("LINEAGE_REVISION_CAP");
    }
    synchronized void checkLineageJointCapacity(LineageJournal.Transfer t)throws IOException{
        var a=new Cell(t.source().key());var b=new Cell(t.destination());
        boolean addA=revision(a.dimension,a.x,a.y,a.z)==0;
        boolean addB=revision(b.dimension,b.x,b.y,b.z)==0;
        if(addA&&addB&&(revisions.size()>MAX_TOTAL_REVISIONS-2
                ||a.chunk().equals(b.chunk())&&revisionChunks.getOrDefault(a.chunk(),Set.of()).size()>MAX_REVISIONS_PER_CHUNK-2))
            throw new IOException("LINEAGE_JOINT_REVISION_CAP");
    }
    synchronized void retireLineageSource(LineageJournal.Transfer t)throws IOException{
        if(!LineageJournal.isProofMarker(receipt(t.outId())))throw new IOException("LINEAGE_SOURCE_PROOF_REQUIRED");
        if(checkLineageSource(t)){
            var k=t.source().key();replaceBlock(k.dimension(),k.x(),k.y(),k.z());
        }
    }
    synchronized void retireLineageDestination(LineageJournal.Transfer t)throws IOException{
        if(!LineageJournal.isProofMarker(receipt(t.inId())))throw new IOException("LINEAGE_DESTINATION_PROOF_REQUIRED");
        if(checkLineageDestination(t)){
            var k=t.destination();replaceBlock(k.dimension(),k.x(),k.y(),k.z());
        }
    }
    /** Same actual Copycats block, changed shape/state, not a replacement material block.
     * Atomically rekey the sparse record without changing any damage/history quantity. */
    public synchronized void remapCopycatShape(SparseStateKey before,SparseStateKey after,String incarnation)throws IOException{
        validateCopycatShape(before,after,incarnation);
        append(frame(out->{out.writeByte(COPYCAT_SHAPE);writeKey(out,before);writeKey(out,after);writeString(out,incarnation);}));
        applyCopycatShape(before,after,incarnation);
    }
    private void validateCopycatShape(SparseStateKey before,SparseStateKey after,String incarnation)throws IOException{
        Cell cell=new Cell(before);long prior=revision(cell.dimension,cell.x,cell.y,cell.z);
        String id=before.fingerprint().split("\\|",2)[0];
        if(!id.startsWith("copycats:")||!after.fingerprint().startsWith(id+"|")||!cell.equals(new Cell(after))
                ||!before.ownerId().equals(after.ownerId())||before.ownerGeneration()!=after.ownerGeneration()
                ||!before.ownerSpace().equals(after.ownerSpace())||before.equals(after)||prior==Long.MAX_VALUE
                ||!incarnation.startsWith((prior+1)+"-")||positionKeys.getOrDefault(cell,Set.of()).size()>(states.containsKey(before)?1:0)
                ||states.containsKey(before)&&!states.get(before).incarnation().startsWith(prior+"-"))
            throw new IOException("COPYCAT_SHAPE_CAS_MISMATCH");
        if(prior==0&&(revisions.size()>=MAX_TOTAL_REVISIONS||revisionChunks.getOrDefault(cell.chunk(),Set.of()).size()>=MAX_REVISIONS_PER_CHUNK))
            throw new IOException("COPYCAT_SHAPE_REVISION_CAP");
    }
    private void applyCopycatShape(SparseStateKey before,SparseStateKey after,String incarnation){
        Cell cell=new Cell(before);var old=states.get(before);clear(cell);setRevision(cell,revision(cell.dimension,cell.x,cell.y,cell.z)+1);
        if(old!=null)putState(new WorldReceiptJournal.State(after,incarnation,old.damage(),old.remainder(),old.materialGeneration(),old.tick(),old.deformation()));
    }
    /** Complete a bounded amount of snapshot work on the server thread. */
    @Override public synchronized int checkpointBatch(int maxObjects) throws IOException {
        if(appendUncertain)throw new IOException("RECOVERY_WAL_APPEND_UNCERTAIN");
        checkReceiptReadHealth();
        if(maxObjects<1)throw new IllegalArgumentException("checkpoint budget must be positive");
        if(!staging){
            if(dirtyChunks.isEmpty()&&dirtyBuckets.isEmpty())return 0;
            staging=true;
            stagedChunks=new HashMap<>(currentChunks);
            stagedBuckets=new HashMap<>(currentBuckets);
            stagedReceiptShards=new HashMap<>(receiptShards);
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
                var body=receiptBytes(bucket);
                var shard=indexReceiptBody(bucket,relative,SCHEMA,body,false);
                writeDurable(root.resolve(relative),blob(RECEIPTS,body));
                stagedReceiptShards.put(bucket,shard);
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
    private static long receiptHash(String id){
        try{return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256")
                .digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8))).getLong();}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static int bucket(String id){return (int)(receiptHash(id)>>>56);}
    private static long candidateKey(long hash,int index){return ((long)(int)(hash>>>24)<<32)|(index&0xffffffffL);}
    private void validateTransition(WorldReceiptJournal.Receipt r,WorldReceiptJournal.State next)throws IOException {
        var prior=lookupReceipt(r.id());
        if(prior!=null&&!prior.key().equals(r.key()))throw new IllegalArgumentException("ApplyId alias");
        if(prior!=null&&(prior.status()==WorldReceiptJournal.Status.APPLIED
                ||prior.status()==WorldReceiptJournal.Status.ABORTED
                ||prior.status()==WorldReceiptJournal.Status.PARTIAL_QUARANTINED))
            throw new IllegalArgumentException("terminal receipt");
        if(next!=null&&(!r.key().equals(next.key())||r.status()!=WorldReceiptJournal.Status.APPLIED))
            throw new IllegalArgumentException("state/receipt mismatch");
        if(prior==null&&receiptCount>=MAX_RECEIPTS)throw new IOException("RECEIPT_LEDGER_CAP_EXCEEDED");
        if(r.pending()!=null && (prior!=null || pendingCount>=WorldCommitCore.MAX_QUEUED))
            throw new IOException("PENDING_PAYLOAD_DUPLICATE_OR_CAP");
        if(next!=null && (next.damage()>0||next.remainder()>0||next.deformation()>0) && !canStore(next.key()))
            throw new IOException("SPARSE_CHUNK_CAP_EXCEEDED");
        if(next!=null && (next.damage()>0||next.remainder()>0||next.deformation()>0)
                && hasPosition(next.key().dimension(),next.key().x(),next.key().y(),next.key().z())
                && !states.containsKey(next.key()))
            throw new IOException("STALE_POSITION_STATE_ALIAS");
    }
    private void applyTransition(WorldReceiptJournal.Receipt r,WorldReceiptJournal.State next){
        // Validation already rejected terminal predecessors; all acceptable
        // predecessors are retained in the active map. No cold I/O after fsync.
        var prior=uncertainReceipts.remove(r.id());
        if(prior==null){receiptCount++;receiptBucketCounts[bucket(r.id())]++;}
        if(prior!=null && prior.pending()!=null) {
            pendingCount--;
            pendingPositions.computeIfPresent(new Cell(prior.key()),(cell,n)->n==1?null:n-1);
        }
        if(r.pending()!=null)addPending(r);
        if(r.status()==WorldReceiptJournal.Status.APPLIED
                ||r.status()==WorldReceiptJournal.Status.ABORTED
                ||r.status()==WorldReceiptJournal.Status.PARTIAL_QUARANTINED)
            recoveredUncertain.remove(r.id());
        registerRecoveryReceipt(r);
        putOverlay(r);
        if(next!=null)putState(next);
    }
    private void registerRecoveryReceipt(WorldReceiptJournal.Receipt r){
        if(r.status()==WorldReceiptJournal.Status.PREPARED||r.status()==WorldReceiptJournal.Status.DEFERRED)
            uncertainReceipts.put(r.id(),r);
        else if(r.status()==WorldReceiptJournal.Status.PARTIAL_QUARANTINED)
            quarantinedReceipts.put(r.id(),r);
    }
    private void putOverlay(WorldReceiptJournal.Receipt r){
        int b=bucket(r.id());
        if(receiptOverlay.computeIfAbsent(b,ignored->new HashMap<>()).put(r.id(),r)==null)overlayCount++;
        dirtyBuckets.add(b);
    }
    private void addPending(WorldReceiptJournal.Receipt r){
        pendingCount++;
        pendingPositions.merge(new Cell(r.key()),1,Integer::sum);
    }
    private void putState(WorldReceiptJournal.State value){
        SparseStateKey key=value.key();ChunkKey chunk=chunk(key);
        if(value.damage()==0&&value.remainder()==0&&value.deformation()==0){removeState(key);return;}
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

    /** Re-encode all schema-1/2/3 committed snapshots and replayed WAL data without
     * modifying the old generation. A crash before the atomic manifest move
     * replays the untouched previous WAL; after it, schema 4 is self-contained.
     */
    private void upgradeSchema() throws IOException {
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
        Map<Integer,ReceiptShard> newShards=new HashMap<>();
        for(int id=0;id<receiptBucketCounts.length;id++){
            if(receiptBucketCounts[id]==0)continue;
            String rel="receipts/"+id+"."+next+".pdr";
            var body=receiptBytes(id);
            newShards.put(id,indexReceiptBody(id,rel,SCHEMA,body,false));
            writeDurable(root.resolve(rel),blob(RECEIPTS,body));
            newBuckets.put(id,rel);
        }
        // The manifest is written last. Do not delete legacy data during migration.
        createWal(next);
        writeManifest(next,newChunks,newBuckets);
        currentChunks.clear();currentChunks.putAll(newChunks);
        currentBuckets.clear();currentBuckets.putAll(newBuckets);
        receiptShards.clear();receiptShards.putAll(newShards);
        receiptOverlay.clear();overlayCount=0;
        generation=next;loadingSchema=SCHEMA;migrated=true;
        dirtyChunks.clear();dirtyBuckets.clear();
    }
    private static int readDiskSchema(Path path)throws IOException {
        try(var in=new DataInputStream(Files.newInputStream(path))){
            if(in.readInt()!=DISK_MAGIC)throw new IOException("unknown PDD checkpoint magic");
            int version=in.readInt();
            if(version<1||version>SCHEMA)throw new IOException("unsupported PDD checkpoint schema "+version);
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
                if(lookupReceipt(r.id())!=null||receiptCount>=MAX_RECEIPTS)throw new IOException("legacy duplicate ApplyId or receipt cap");
                registerRecoveryReceipt(r);putOverlay(r);
                receiptCount++;receiptBucketCounts[bucket(r.id())]++;
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
            var body=receiptBytes(id);
            receiptShards.put(id,indexReceiptBody(id,rel,SCHEMA,body,false));
            writeDurable(root.resolve(rel),blob(RECEIPTS,body));
            currentBuckets.put(id,rel);
        }
        createWal(1);
        writeManifest(1,currentChunks,currentBuckets);
        receiptOverlay.clear();overlayCount=0;
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
        var shard=indexReceiptBody(expected,rel,loadingSchema,readBlob(checkedFile(rel),RECEIPTS),true);
        receiptShards.put(expected,shard);
        receiptCount+=shard.count();receiptBucketCounts[expected]=shard.count();
    }
    private ReceiptShard indexReceiptBody(int expected,String relative,int schema,byte[] body,boolean restoreActive)throws IOException {
        try(var in=new DataInputStream(new ByteArrayInputStream(body))){
            int actual=in.readUnsignedByte();if(actual!=expected)throw new IOException("receipt bucket mismatch");
            int count=in.readInt();
            if(count<0||count>MAX_RECEIPTS||restoreActive&&count>MAX_RECEIPTS-receiptCount
                    ||!restoreActive&&count!=receiptBucketCounts[expected])throw new IOException("invalid receipt count");
            int[] offsets=new int[count+1],crcs=new int[count];long[] candidates=new long[count];
            String previous=null;
            for(int i=0;i<count;i++){
                int start=body.length-in.available();offsets[i]=start;
                var r=readReceipt(in,schema);
                long hash=receiptHash(r.id());
                if((int)(hash>>>56)!=expected||previous!=null&&previous.compareTo(r.id())>=0)
                    throw new IOException("duplicate/unsorted/invalid receipt ledger entry");
                previous=r.id();int end=body.length-in.available();
                CRC32 check=new CRC32();check.update(body,start,end-start);crcs[i]=(int)check.getValue();
                candidates[i]=candidateKey(hash,i);
                if(restoreActive){
                    registerRecoveryReceipt(r);
                    if(r.pending()!=null){
                        if(pendingCount>=WorldCommitCore.MAX_QUEUED)throw new IOException("pending capacity exceeded");
                        addPending(r);
                    }
                }
            }
            if(in.available()!=0)throw new IOException("receipt snapshot trailing bytes");
            offsets[count]=body.length;Arrays.sort(candidates);
            return new ReceiptShard(relative,schema,crc(body),offsets,crcs,candidates);
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
            out.writeInt(receiptBucketCounts[id]);
            var changes=new TreeMap<>(receiptOverlay.getOrDefault(id,Map.of())).entrySet().iterator();
            var change=changes.hasNext()?changes.next():null;
            ReceiptShard base=receiptShards.get(id);
            int written=0;
            if(base!=null){
                byte[] body;
                try{
                    body=readBlob(checkedFile(base.relative()),RECEIPTS,base.schema());
                    if(crc(body)!=base.bodyCrc())throw new IOException("receipt shard changed since indexing");
                }catch(IOException failure){receiptReadFault=failure;throw failure;}
                try(var old=new DataInputStream(new ByteArrayInputStream(body))){
                    if(old.readUnsignedByte()!=id||old.readInt()!=base.count())throw new IOException("receipt index mismatch");
                    for(int i=0;i<base.count();i++){
                        var r=readReceipt(old,base.schema());
                        while(change!=null&&change.getKey().compareTo(r.id())<0){
                            writeReceipt(out,change.getValue());written++;change=changes.hasNext()?changes.next():null;
                        }
                        if(change!=null&&change.getKey().equals(r.id())){
                            writeReceipt(out,change.getValue());change=changes.hasNext()?changes.next():null;
                        }else writeReceipt(out,r);
                        written++;
                    }
                    if(old.available()!=0)throw new IOException("receipt index trailing bytes");
                }
            }
            while(change!=null){writeReceipt(out,change.getValue());written++;change=changes.hasNext()?changes.next():null;}
            if(written!=receiptBucketCounts[id])throw new IOException("receipt merged count mismatch");
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
        receiptShards.clear();receiptShards.putAll(stagedReceiptShards);
        stagedReceiptShards=null;receiptOverlay.clear();overlayCount=0;
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
        if(appendUncertain)throw new IOException("RECOVERY_WAL_APPEND_UNCERTAIN");
        checkReceiptReadHealth();
        if(walBytes+frame.length>MAX_WAL_BYTES)throw new IOException("RECOVERY_WAL_CAP_EXCEEDED");
        Path path=walPath(generation);
        try(var channel=FileChannel.open(path,APPEND,WRITE)){
            if(channel.size()!=walBytes)throw new IOException("recovery WAL changed externally");
            ByteBuffer b=ByteBuffer.wrap(frame);while(b.hasRemaining())channel.write(b);
            channel.force(true);walBytes+=frame.length;
        }catch(IOException uncertain){appendUncertain=true;throw uncertain;}
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
                    }else if(type==RELOCATION&&loadingSchema>=4){
                        var receipt=readReceipt(rec);var expected=rec.readBoolean()?readState(rec):null;
                        var next=readState(rec);long fromRevision=rec.readLong(),toRevision=rec.readLong();
                        validateRelocation(receipt,expected,next,fromRevision,toRevision);
                        applyRelocation(receipt,next,fromRevision,toRevision);
                    }else if(type==COPYCAT_SHAPE&&loadingSchema>=5){
                        var before=readKey(rec);var after=readKey(rec);var token=readString(rec);
                        validateCopycatShape(before,after,token);applyCopycatShape(before,after,token);
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
        return readBlob(file,type,loadingSchema);
    }
    private byte[] readBlob(Path file,int type,int schema)throws IOException {
        if(!Files.isRegularFile(file))throw new IOException("missing checkpoint file: "+file);
        long length=Files.size(file);if(length<20||length>MAX_BLOB_BYTES+20L)
            throw new IOException("invalid checkpoint file size: "+file);
        try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))){
            if(in.readInt()!=DISK_MAGIC||in.readInt()!=schema||in.readInt()!=type)
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
        out.writeLong(s.materialGeneration());out.writeLong(s.tick());out.writeByte(s.deformation());
    }
    private WorldReceiptJournal.State readState(DataInputStream in)throws IOException {
        return new WorldReceiptJournal.State(readKey(in),readString(in),
                loadingSchema==1?in.readUnsignedByte():in.readUnsignedShort(),
                in.readUnsignedByte(),in.readLong(),in.readLong(),loadingSchema>=4?in.readUnsignedByte():0);
    }
    private static void writeReceipt(DataOutputStream out,WorldReceiptJournal.Receipt r)throws IOException {
        writeString(out,r.id());writeKey(out,r.key());out.writeByte(r.status().ordinal());
        writeString(out,r.reason());out.writeLong(r.tick());
        if(r.status()==WorldReceiptJournal.Status.DEFERRED){
            var p=r.pending();out.writeBoolean(p!=null);
            if(p!=null){
                var i=p.intent();var m=i.material();var s=p.settings();
                writeString(out,i.expectedIncarnation());out.writeDouble(i.sealedQ());
                out.writeInt(m.hardness());out.writeInt(m.threshold());out.writeInt(m.ductilityPercent());
                out.writeInt(m.elasticityPercent());out.writeBoolean(m.unbreakable());out.writeDouble(m.yieldThreshold());
                out.writeLong(i.materialGeneration());out.writeBoolean(i.fFirstPreDamped());writeString(out,i.materialRevision());
                com.physicaldestroydeform.deform.MovePlan.Evidence.write(out,i.deformationEvidence());
                out.writeDouble(s.minHardnessFactor());out.writeDouble(s.hardnessExponent());
                out.writeDouble(s.minElasticFactor());out.writeDouble(s.elasticExponent());
                out.writeDouble(s.damageGain());out.writeDouble(s.deformationGain());
                out.writeLong(p.order());out.writeLong(p.expiresGameTick());
            }
        }
    }
    private WorldReceiptJournal.Receipt readReceipt(DataInputStream in)throws IOException {
        return readReceipt(in,loadingSchema);
    }
    private static WorldReceiptJournal.Receipt readReceipt(DataInputStream in,int schema)throws IOException {
        String id=readString(in);SparseStateKey key=readKey(in);int ordinal=in.readUnsignedByte();
        if(ordinal>=WorldReceiptJournal.Status.values().length)throw new IOException("unknown receipt status");
        var status=WorldReceiptJournal.Status.values()[ordinal];
        String reason=readString(in);long tick=in.readLong();
        WorldReceiptJournal.Pending pending=null;
        if(schema>=3 && status==WorldReceiptJournal.Status.DEFERRED && in.readBoolean()){
            String incarnation=readString(in);double q=in.readDouble();
            var material=new com.physicaldestroydeform.MaterialProperties(in.readInt(),in.readInt(),
                    in.readInt(),in.readInt(),in.readBoolean(),in.readDouble());
            long generation=in.readLong();boolean preDamped=in.readBoolean();
            var intent=new WorldCommitCore.Intent(id,key,incarnation,q,material,generation,tick,preDamped,schema>=5?readString(in):"",
                    schema>=5?com.physicaldestroydeform.deform.MovePlan.Evidence.read(in,schema>=6):null);
            var settings=new com.physicaldestroydeform.DamageModel.Settings(in.readDouble(),in.readDouble(),
                    in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble());
            pending=new WorldReceiptJournal.Pending(intent,settings,in.readLong(),in.readLong());
        }
        return new WorldReceiptJournal.Receipt(id,key,status,reason,tick,pending);
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
