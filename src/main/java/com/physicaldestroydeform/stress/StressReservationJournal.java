package com.physicaldestroydeform.stress;

import com.physicaldestroydeform.*;
import com.physicaldestroydeform.world.WorldCommitCore;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.CRC32C;
import static com.physicaldestroydeform.stress.StressPropagation.*;

/** Durable reservation/handoff boundary. No source F calculation and no game mutation.
 * RESERVED may be replanned from the exact saved roots, policy, settings and original
 * game-clock origin and frozen observations. SEALED outputs are append-only and use idempotent ApplyIds.
 * CLOSED records can be compacted only after durable commit receipt reconciliation.
 */
public final class StressReservationJournal {
    public static final int MAX_RECORDS=8192,MAX_RECORD_BYTES=4*1024*1024;
    public static final long MAX_STORE_BYTES=64L*1024*1024;
    private static final int MAGIC=0x50535452,VERSION=4;
    private static final int CLOSURE_HEADROOM=2048;
    public static final class CapacityException extends IOException {
        CapacityException(String reason){super(reason);}
    }
    public enum Phase { RESERVED, SEALED, CLOSED }
    public record Reservation(String creditId,long materialGeneration,long createdGameTick,
                              Policy policy,DamageModel.Settings settings,List<Seed> seeds,List<WorldCommitCore.Intent> origins) {
        public Reservation(String creditId,long materialGeneration,long createdGameTick,Policy policy,DamageModel.Settings settings,List<Seed> seeds){
            this(creditId,materialGeneration,createdGameTick,policy,settings,seeds,List.of());
        }
        public Reservation {
            text(creditId);Objects.requireNonNull(policy);Objects.requireNonNull(settings);
            if(materialGeneration<0||createdGameTick<0||seeds.isEmpty()||seeds.size()>8192)
                throw new IllegalArgumentException("invalid stress reservation");
            var sorted=new ArrayList<>(seeds);sorted.sort(Comparator.comparing(s->s.node().cell()));
            var unique=new HashSet<Cell>();double sum=0;
            for(var seed:sorted){if(!unique.add(seed.node().cell()))throw new IllegalArgumentException("duplicate seed");sum+=seed.q();}
            q(sum);seeds=List.copyOf(sorted);origins=List.copyOf(origins);
            if(!origins.isEmpty()){
                var byCell=new HashMap<Cell,WorldCommitCore.Intent>();
                var applyIds=new HashSet<String>();
                for(var origin:origins){
                    if(!applyIds.add(origin.applyId())||origin.materialGeneration()!=materialGeneration||byCell.put(StressCommitHandoff.cellFor(origin.key()),origin)!=null)
                        throw new IllegalArgumentException("invalid origin reservation");
                }
                if(byCell.size()!=seeds.size())throw new IllegalArgumentException("origin/seed count");
                for(var seed:seeds){
                    var origin=byCell.get(seed.node().cell());
                    if(origin==null||!origin.expectedIncarnation().equals(seed.node().incarnation())
                            ||!origin.key().fingerprint().equals(seed.node().fingerprint())||!origin.material().equals(seed.node().material())||!origin.materialRevision().equals(seed.node().materialRevision())
                            ||!near(DamageModel.plan(origin.sealedQ(),origin.material(),new BlockDamageState(),settings).stressQ(),seed.q()))
                        throw new IllegalArgumentException("origin stress partition mismatch");
                }
            }
        }
        public double reservedQ(){return seeds.stream().mapToDouble(Seed::q).sum();}
        public Session resumePlanning(long gameTick){
            if(gameTick<createdGameTick)throw new IllegalStateException("world game clock rolled back");
            return new Session(seeds,policy,settings,createdGameTick);
        }
    }
    public record Entry(Reservation reservation,Phase phase,List<PlannedNode> plans,Ledger ledger,String reason,Map<Cell,Probe> snapshot) {
        public Entry(Reservation reservation,Phase phase,List<PlannedNode> plans,Ledger ledger,String reason){this(reservation,phase,plans,ledger,reason,Map.of());}
        public boolean holdsOrigins(){return phase==Phase.RESERVED||"ROOTS_PENDING".equals(reason);}
        public Entry {
            Objects.requireNonNull(reservation);Objects.requireNonNull(phase);text(reason);plans=List.copyOf(plans);snapshot=Map.copyOf(snapshot);
            if(snapshot.size()>65536)throw new IllegalArgumentException("stress snapshot cap");
            if(phase==Phase.RESERVED){
                if(!plans.isEmpty()||ledger!=null)throw new IllegalArgumentException("unsealed reservation has outputs");
            }else{
                Objects.requireNonNull(ledger);
                if((phase==Phase.CLOSED&&(!ledger.finished()||ledger.pendingQ()!=0))||!near(ledger.accountedQ(),reservation.reservedQ())
                        ||!near(ledger.reservedQ(),reservation.reservedQ())||plans.size()>reservation.policy.maxNodes())
                    throw new IllegalArgumentException("unbalanced sealed stress ledger");
                var cells=new HashSet<Cell>();var roots=new HashSet<Cell>();var owners=new HashSet<String>();
                for(var s:reservation.seeds){roots.add(s.node().cell());owners.add(s.node().cell().owner());}
                double direct=0,absorbed=0,plastic=0;
                for(var p:plans){
                    if(p.depth()<1||p.depth()>reservation.policy.maxDepth()||!cells.add(p.node().cell())
                            ||roots.contains(p.node().cell())||!owners.contains(p.node().cell().owner()))
                        throw new IllegalArgumentException("invalid stress output target");
                    var computed=DamageModel.plan(p.incomingQ(),p.node().material(),new BlockDamageState(),reservation.settings);
                    if(!computed.equals(p.plan()))throw new IllegalArgumentException("changed sealed numerical plan");
                    direct+=computed.directQ();absorbed+=computed.absorbedQ();plastic+=computed.plasticQ();
                }
                q(ledger.reservedQ());q(ledger.absorbedQ());q(ledger.directQ());q(ledger.plasticUnusedQ());q(ledger.unusedQ());q(ledger.pendingQ());
                if(!near(direct,ledger.directQ())||!near(absorbed,ledger.absorbedQ())||!near(plastic,ledger.plasticUnusedQ())
                        ||ledger.uniqueNodes()!=plans.size())throw new IllegalArgumentException("sealed outputs mismatch ledger");
                double closed=0;for(double value:ledger.reasons().values()){q(value);closed+=value;}
                if(!near(closed,ledger.unusedQ()))throw new IllegalArgumentException("unused reasons mismatch");
            }
        }
    }
    private final Path directory;
    private final StressClosedArchive archive;
    private final Map<String,Entry> entries=new LinkedHashMap<>();
    private final Map<String,Long> sizes=new HashMap<>();
    private final Map<String,String> sourceOwners=new HashMap<>();
    private long bytes;
    private boolean faulted;
    public StressReservationJournal(Path directory)throws IOException {
        this.directory=directory.toAbsolutePath();Files.createDirectories(this.directory);
        archive=new StressClosedArchive(this.directory,sourceOwners);
        try(var paths=Files.list(this.directory)){
            // Bound collection BEFORE sorting; an oversized directory must not
            // allocate an unbounded list before the record cap can reject it.
            var records=paths.filter(x->x.getFileName().toString().endsWith(".psr"))
                    .limit(MAX_RECORDS+1L).toList();
            if(records.size()>MAX_RECORDS)throw new IOException("stress record count cap exceeded");
            for(Path p:records.stream().sorted().toList()){
                long size=Files.size(p);
                if(size>MAX_RECORD_BYTES||size<16||entries.size()>=MAX_RECORDS||bytes+size>MAX_STORE_BYTES+(long)MAX_RECORDS*CLOSURE_HEADROOM)
                    throw new IOException("stress storage bound exceeded");
                Entry entry=decode(Files.readAllBytes(p));String id=entry.reservation.creditId;
                if(!p.getFileName().toString().equals(filename(id)))throw new IOException("stress filename/id conflict");
                if(archive.contains(id)){
                    if(!archive.matchesEntry(entry))throw new IOException("stress archive/active conflict");
                    archive.confirmDurable(id);Files.delete(p);forceDirectory(this.directory);continue;
                }
                if(entries.putIfAbsent(id,entry)!=null)
                    throw new IOException("stress filename/id conflict");
                for(var origin:entry.reservation.origins)
                    if(sourceOwners.putIfAbsent(origin.applyId(),id)!=null)throw new IOException("source origin reserved twice");
                sizes.put(id,size);bytes+=size;
            }
        }
        if(bytes>MAX_STORE_BYTES+(long)entries.size()*CLOSURE_HEADROOM)throw new IOException("stress closure headroom exceeded");
    }
    public synchronized boolean reserve(Reservation reservation)throws IOException {
        ensureHealthy();Entry old=entries.get(reservation.creditId);
        if(old!=null){
            if(!old.reservation.equals(reservation))throw new IllegalArgumentException("Credit reused with different reservation");
            return false;
        }
        if(archive.contains(reservation.creditId)){
            if(!archive.matchesReservation(reservation,false))throw new IllegalArgumentException("Credit reused with different reservation");
            return false;
        }
        for(var origin:reservation.origins)
            if(sourceOwners.containsKey(origin.applyId()))throw new IllegalArgumentException("source origin already reserved");
        if(entries.size()>=MAX_RECORDS)throw new CapacityException("stress active record capacity");
        persist(new Entry(reservation,Phase.RESERVED,List.of(),null,"RESERVED"));return true;
    }
    public synchronized void seal(String creditId,Session session)throws IOException {
        ensureHealthy();Entry old=require(creditId);
        var r=old.reservation;
        if(!session.matchesOrigin(r.seeds,r.policy,r.settings,r.createdGameTick))
            throw new IllegalArgumentException("session does not belong to reservation");
        Entry next=new Entry(old.reservation,Phase.SEALED,session.plans(),session.ledger(),"PLANNED_NOT_APPLIED");
        if(old.phase!=Phase.RESERVED){
            if(old.plans.equals(next.plans)&&Objects.equals(old.ledger,next.ledger))return;
            throw new IllegalStateException("sealed stress plan is immutable");
        }
        persist(next);
    }
    /** Persist the frozen graph even before the first output, so restart cannot silently replan a different path. */
    public synchronized void snapshot(String id,Map<Cell,Probe> snapshot)throws IOException {
        ensureHealthy();var old=require(id);
        if(old.phase!=Phase.RESERVED)return;
        // An unchanged frozen graph is already durable. Do not encode/CRC,
        // atomically replace and fsync the same bytes on every blocked retry.
        if(old.snapshot.equals(snapshot))return;
        persist(new Entry(old.reservation,old.phase,old.plans,old.ledger,old.reason,snapshot));
    }
    /** Append-only partial publication. Every new output must have passed the runtime's bounded live CAS. */
    public synchronized void sealProgress(String id,Session session,Map<Cell,Probe> snapshot)throws IOException {
        ensureHealthy();var old=require(id);var r=old.reservation;
        if(!session.matchesOrigin(r.seeds,r.policy,r.settings,r.createdGameTick))throw new IllegalArgumentException("wrong session");
        var plans=session.partialPlans();
        if(old.phase==Phase.CLOSED)throw new IllegalStateException("already closed");
        if(plans.size()<old.plans.size()||!plans.subList(0,old.plans.size()).equals(old.plans))
            throw new IllegalStateException("changed published stress prefix");
        Ledger ledger=session.ledger();
        String reason=session.rootsExpanded()?"PARTIAL_OR_FINAL_PLANS_DURABLE":"ROOTS_PENDING";
        // All fields serialized in this transition must match before eliding
        // a write. A changed graph, accounting, output or phase still fsyncs.
        if(old.phase==Phase.SEALED&&old.plans.equals(plans)&&old.ledger.equals(ledger)
                &&old.reason.equals(reason)&&old.snapshot.equals(snapshot))return;
        persist(new Entry(r,Phase.SEALED,plans,ledger,reason,snapshot));
    }
    /** A changed graph cannot spend its residual on replacement blocks. Retain that Q under
     * the original deadline while allowing the original direct intents to perform their own CAS. */
    public synchronized void deferResidual(String id,String reason)throws IOException {
        ensureHealthy();var old=require(id);
        if(old.phase==Phase.CLOSED)return;
        Ledger ledger=old.ledger;
        if(ledger==null){double q=old.reservation.reservedQ();ledger=new Ledger(q,0,0,0,0,q,0,true,Map.of());}
        else ledger=new Ledger(ledger.reservedQ(),ledger.absorbedQ(),ledger.directQ(),ledger.plasticUnusedQ(),
                ledger.unusedQ(),ledger.pendingQ(),ledger.uniqueNodes(),true,ledger.reasons());
        persistClosing(new Entry(old.reservation,Phase.SEALED,old.plans,ledger,reason,old.snapshot));
    }
    /** Close only unconsumed continuation; already handed-off Q remains in its original partitions. */
    public synchronized void closeResidual(String id,String reason)throws IOException {
        ensureHealthy();var old=require(id);
        if(old.phase==Phase.CLOSED)return;
        if(old.phase==Phase.RESERVED){cancelUnplanned(id,reason);return;}
        var l=old.ledger;var reasons=new EnumMap<Unused,Double>(Unused.class);reasons.putAll(l.reasons());
        if(l.pendingQ()>0)reasons.merge(Unused.STOPPED,l.pendingQ(),Double::sum);
        var closed=new Ledger(l.reservedQ(),l.absorbedQ(),l.directQ(),l.plasticUnusedQ(),l.unusedQ()+l.pendingQ(),0,l.uniqueNodes(),true,reasons);
        persistClosing(new Entry(old.reservation,Phase.SEALED,old.plans,closed,reason,old.snapshot));
    }
    public synchronized List<Entry> entries(){return List.copyOf(entries.values());}
    /** Call only after EVERY output ApplyId is durably queued or terminal in the commit journal. */
    public synchronized void acknowledgeHandoff(String creditId)throws IOException {
        ensureHealthy();Entry old=require(creditId);
        if(old.phase==Phase.CLOSED)return;
        if(old.phase!=Phase.SEALED||!old.ledger.finished()||old.ledger.pendingQ()!=0)throw new IllegalStateException("cannot close an unfinished handoff");
        persistClosing(new Entry(old.reservation,Phase.CLOSED,old.plans,old.ledger,"HANDED_TO_DURABLE_COMMIT_JOURNAL",old.snapshot));
    }
    public synchronized void cancelUnplanned(String creditId,String reason)throws IOException {
        ensureHealthy();Entry old=require(creditId);
        if(old.phase==Phase.CLOSED)return;
        if(old.phase!=Phase.RESERVED)throw new IllegalStateException("sealed outputs require receipt reconciliation");
        double q=old.reservation.reservedQ();
        persistClosing(new Entry(old.reservation,Phase.CLOSED,List.of(),
                new Ledger(q,0,0,0,q,0,0,true,q==0?Map.of():Map.of(Unused.STOPPED,q)),reason));
    }
    /** Caller must have verified every source and derived durable receipt. The compact
     * file is authoritative before the old full file is removed. No Q is reissued. */
    public synchronized void compactHandoff(String id)throws IOException {
        ensureHealthy();if(archive.contains(id))return;
        Entry old=require(id);
        if(old.phase==Phase.RESERVED||!old.ledger.finished()||old.ledger.pendingQ()!=0)
            throw new IllegalStateException("cannot compact unfinished stress");
        try{
            archive.store(old);
            Files.delete(directory.resolve(filename(id)));forceDirectory(directory);
            entries.remove(id);bytes-=sizes.remove(id);
        }catch(IOException failure){faulted=true;throw failure;}
    }
    public synchronized boolean isArchived(String id){return archive.contains(id);}
    public synchronized boolean matchesArchivedRetry(Reservation r)throws IOException{return archive.matchesReservation(r,true);}
    public synchronized Ledger archivedLedger(String id){return archive.ledger(id);}
    public synchronized String sourceOwner(String originId){return sourceOwners.get(originId);}
    public synchronized int activeCount(){return entries.size();}
    public synchronized int archivedCount(){return archive.size();}
    public synchronized long activeBytes(){return bytes;}
    public synchronized Entry get(String creditId){return entries.get(creditId);}
    public synchronized List<Entry> unfinished(){return entries.values().stream().filter(e->e.phase!=Phase.CLOSED).toList();}
    public synchronized int size(){return entries.size()+archive.size();}
    public synchronized boolean faulted(){return faulted;}
    private Entry require(String id){Entry e=entries.get(id);if(e==null)throw new IllegalArgumentException("unreserved Credit");return e;}
    private void ensureHealthy()throws IOException{if(faulted)throw new IOException("stress journal faulted; reopen and reconcile");}
    private void persist(Entry entry)throws IOException {
        persist(entry,false);
    }
    private void persistClosing(Entry entry)throws IOException {
        try{persist(entry,true);}catch(CapacityException pressure){
            // No new plan can be published after a final residual closure. The
            // frozen graph is no longer needed for replay; retain all paid outputs.
            persist(new Entry(entry.reservation,entry.phase,entry.plans,entry.ledger,entry.reason,Map.of()),true);
        }
    }
    private void persist(Entry entry,boolean closing)throws IOException {
        byte[] payload=encode(entry);String id=entry.reservation.creditId;
        long nextBytes=bytes-sizes.getOrDefault(id,0L)+payload.length;
        long count=entries.size()+(entries.containsKey(id)?0:1);
        if(!closing&&(payload.length>MAX_RECORD_BYTES-CLOSURE_HEADROOM||nextBytes+count*CLOSURE_HEADROOM>MAX_STORE_BYTES))
            throw new CapacityException("stress active storage pressure");
        // Old v4 files were admitted without metadata headroom. Permit only
        // bounded closing metadata growth for them; never admit new work here.
        if(closing&&nextBytes>MAX_STORE_BYTES&&(nextBytes>MAX_STORE_BYTES+count*CLOSURE_HEADROOM||nextBytes-bytes>CLOSURE_HEADROOM))
            throw new CapacityException("stress closure storage pressure");
        Path temp=null;
        try {
            temp=Files.createTempFile(directory,"pending-",".tmp");
            try(FileChannel channel=FileChannel.open(temp,StandardOpenOption.WRITE)){
                ByteBuffer buffer=ByteBuffer.wrap(payload);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
            }
            Files.move(temp,directory.resolve(filename(id)),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            forceDirectory(directory);
            entries.put(id,entry);sizes.put(id,(long)payload.length);bytes=nextBytes;
            for(var origin:entry.reservation.origins)sourceOwners.put(origin.applyId(),id);
        }catch(IOException failure){faulted=true;throw failure;}
        finally{if(temp!=null)Files.deleteIfExists(temp);}
    }
    static void forceDirectory(Path directory)throws IOException {
        try(FileChannel parent=FileChannel.open(directory,StandardOpenOption.READ)){parent.force(true);}
    }
    /** Stable namespace is independent of input list order or process-local object hashes. */
    public static String applyId(String creditId,Cell cell){
        try {
            var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){
                out.writeUTF("PDD/STRESS/1");out.writeUTF(creditId);writeCell(out,cell);
            }
            return "STRESS:"+hash(bytes.toByteArray());
        }catch(IOException impossible){throw new UncheckedIOException(impossible);}
    }
    private static String filename(String id){return hash(id.getBytes(java.nio.charset.StandardCharsets.UTF_8))+".psr";}
    static String hash(byte[] bytes){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    static byte[] encode(Entry e)throws IOException {
        var buffer=new ByteArrayOutputStream();try(var out=new DataOutputStream(buffer)){
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeUTF(REVISION);out.writeUTF(DamageModel.ALGORITHM_REVISION);
            var r=e.reservation;out.writeUTF(r.creditId);out.writeLong(r.materialGeneration);out.writeLong(r.createdGameTick);
            var p=r.policy;out.writeInt(p.maxDepth());out.writeInt(p.maxNodes());out.writeInt(p.maxNodesPerTick());out.writeDouble(p.minQ());out.writeLong(p.ttlTicks());
            var d=r.settings;out.writeDouble(d.minHardnessFactor());out.writeDouble(d.hardnessExponent());out.writeDouble(d.minElasticFactor());out.writeDouble(d.elasticExponent());out.writeDouble(d.damageGain());out.writeDouble(d.deformationGain());
            out.writeInt(r.seeds.size());for(var s:r.seeds){writeNode(out,s.node());out.writeDouble(s.q());out.writeBoolean(s.direction()!=null);if(s.direction()!=null){out.writeDouble(s.direction().x());out.writeDouble(s.direction().y());out.writeDouble(s.direction().z());}}
            out.writeInt(r.origins.size());for(var origin:r.origins){
                out.writeUTF(origin.applyId());writeNode(out,new Node(StressCommitHandoff.cellFor(origin.key()),origin.key().fingerprint(),origin.expectedIncarnation(),origin.material(),origin.materialRevision()));
                out.writeDouble(origin.sealedQ());out.writeLong(origin.eventTick());out.writeBoolean(origin.fFirstPreDamped());
                com.physicaldestroydeform.deform.MovePlan.Evidence.write(out,origin.deformationEvidence());
            }
            out.writeByte(e.phase.ordinal());out.writeUTF(e.reason);out.writeInt(e.plans.size());
            for(var n:e.plans){writeNode(out,n.node());out.writeInt(n.depth());out.writeDouble(n.incomingQ());}
            if(e.ledger!=null){var l=e.ledger;out.writeDouble(l.reservedQ());out.writeDouble(l.absorbedQ());out.writeDouble(l.directQ());out.writeDouble(l.plasticUnusedQ());out.writeDouble(l.unusedQ());out.writeInt(l.uniqueNodes());out.writeDouble(l.pendingQ());out.writeBoolean(l.finished());
                out.writeInt(l.reasons().size());for(var reason:Unused.values())if(l.reasons().containsKey(reason)){out.writeByte(reason.ordinal());out.writeDouble(l.reasons().get(reason));}}
            out.writeInt(e.snapshot.size());
            for(var item:new TreeMap<>(e.snapshot).entrySet()){
                writeCell(out,item.getKey());out.writeByte(item.getValue().state().ordinal());
                if(item.getValue().node()!=null)writeNode(out,item.getValue().node());
            }
        }
        byte[] data=buffer.toByteArray();CRC32C crc=new CRC32C();crc.update(data);var framed=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(framed)){out.writeInt(data.length);out.write(data);out.writeInt((int)crc.getValue());}
        if(framed.size()>MAX_RECORD_BYTES)throw new CapacityException("stress record too large");return framed.toByteArray();
    }
    static Entry decode(byte[] bytes)throws IOException {
        if(bytes.length>MAX_RECORD_BYTES||bytes.length<16)throw new IOException("invalid stress record size");
        try(var frame=new DataInputStream(new ByteArrayInputStream(bytes))){
            int length=frame.readInt();if(length!=bytes.length-8)throw new IOException("truncated stress record");
            byte[] data=frame.readNBytes(length);CRC32C crc=new CRC32C();crc.update(data);
            if(frame.readInt()!=(int)crc.getValue())throw new IOException("stress CRC mismatch");
            try(var in=new DataInputStream(new ByteArrayInputStream(data))){
                if(in.readInt()!=MAGIC)throw new IOException("unsupported stress magic");
                int version=in.readInt();
                if(version<1||version>VERSION||!in.readUTF().equals(REVISION)||!in.readUTF().equals(DamageModel.ALGORITHM_REVISION))throw new IOException("unsupported stress schema/policy revision");
                String id=in.readUTF();long generation=in.readLong(),tick=in.readLong();
                Policy policy=new Policy(in.readInt(),in.readInt(),in.readInt(),in.readDouble(),in.readLong());
                var settings=new DamageModel.Settings(in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble());
                int n=count(in,8192);List<Seed> seeds=new ArrayList<>();
                for(int i=0;i<n;i++){Node node=readNode(in,version);double q=in.readDouble();Direction direction=in.readBoolean()?new Direction(in.readDouble(),in.readDouble(),in.readDouble()):null;seeds.add(new Seed(node,q,direction));}
                var origins=new ArrayList<WorldCommitCore.Intent>();
                if(version>=2){int originCount=count(in,8192);for(int i=0;i<originCount;i++){
                    String apply=in.readUTF();Node node=readNode(in,version);
                    origins.add(new WorldCommitCore.Intent(apply,StressCommitHandoff.keyFor(node.cell(),node.fingerprint()),node.incarnation(),in.readDouble(),node.material(),generation,in.readLong(),in.readBoolean(),node.materialRevision(),
                            version>=3?com.physicaldestroydeform.deform.MovePlan.Evidence.read(in,version>=4):null));
                }}
                Reservation reservation=new Reservation(id,generation,tick,policy,settings,seeds,origins);
                int phase=in.readUnsignedByte();if(phase>=Phase.values().length)throw new IOException("invalid phase");String reason=in.readUTF();
                n=count(in,policy.maxNodes());List<PlannedNode> plans=new ArrayList<>();
                for(int i=0;i<n;i++){Node node=readNode(in,version);int depth=in.readInt();double q=in.readDouble();plans.add(new PlannedNode(node,depth,q,DamageModel.plan(q,node.material(),new BlockDamageState(),settings)));}
                Ledger ledger=null;if(phase!=Phase.RESERVED.ordinal()){
                    double reserved=in.readDouble(),absorbed=in.readDouble(),direct=in.readDouble(),plastic=in.readDouble(),unused=in.readDouble();int nodes=in.readInt();
                    double pending=version>=2?in.readDouble():0;boolean finished=version<2||in.readBoolean();
                    n=count(in,Unused.values().length);Map<Unused,Double> reasons=new EnumMap<>(Unused.class);
                    for(int i=0;i<n;i++){int kind=in.readUnsignedByte();if(kind>=Unused.values().length||reasons.put(Unused.values()[kind],in.readDouble())!=null)throw new IOException("invalid unused reason");}
                    ledger=new Ledger(reserved,absorbed,direct,plastic,unused,pending,nodes,finished,reasons);
                }
                Map<Cell,Probe> snapshot=new TreeMap<>();
                if(version>=2){n=count(in,65536);for(int i=0;i<n;i++){
                    Cell cell=readCell(in);int state=in.readUnsignedByte();if(state>=State.values().length)throw new IOException("snapshot state");
                    Probe probe=new Probe(State.values()[state],state==State.SOLID.ordinal()?readNode(in,version):null);
                    if(probe.node()!=null&&!probe.node().cell().equals(cell)||snapshot.put(cell,probe)!=null)throw new IOException("snapshot cell");
                }}
                if(in.available()!=0)throw new IOException("trailing stress payload");return new Entry(reservation,Phase.values()[phase],plans,ledger,reason,snapshot);
            }
        }catch(IllegalArgumentException|IllegalStateException invalid){throw new IOException("invalid stress payload",invalid);}
    }
    private static int count(DataInputStream in,int max)throws IOException{int n=in.readInt();if(n<0||n>max)throw new IOException("stress collection cap");return n;}
    private static void writeCell(DataOutputStream out,Cell c)throws IOException{out.writeUTF(c.owner());out.writeInt(c.x());out.writeInt(c.y());out.writeInt(c.z());}
    private static Cell readCell(DataInputStream in)throws IOException{return new Cell(in.readUTF(),in.readInt(),in.readInt(),in.readInt());}
    private static void writeNode(DataOutputStream out,Node n)throws IOException{
        writeCell(out,n.cell());out.writeUTF(n.fingerprint());out.writeUTF(n.incarnation());var m=n.material();out.writeInt(m.hardness());out.writeInt(m.threshold());out.writeInt(m.ductilityPercent());out.writeInt(m.elasticityPercent());out.writeBoolean(m.unbreakable());out.writeDouble(m.yieldThreshold());out.writeUTF(n.materialRevision());
    }
    private static Node readNode(DataInputStream in,int version)throws IOException{return new Node(readCell(in),in.readUTF(),in.readUTF(),new MaterialProperties(in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readBoolean(),in.readDouble()),version>=3?in.readUTF():"");}
    private static boolean near(double a,double b){return Double.isFinite(a)&&Double.isFinite(b)&&Math.abs(a-b)<=Math.max(1e-8,Math.abs(b)*1e-10);}
    private static void q(double q){if(!Double.isFinite(q)||q<0)throw new IllegalArgumentException("invalid stress amount");}
    private static void text(String s){if(s==null||s.isBlank()||s.length()>4096)throw new IllegalArgumentException("invalid stress identity/reason");}
}
