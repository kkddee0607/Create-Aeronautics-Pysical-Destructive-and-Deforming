package com.physicaldestroydeform.stress;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32C;
import static com.physicaldestroydeform.stress.StressPropagation.*;
import static com.physicaldestroydeform.stress.StressReservationJournal.*;

/** Compact, immutable no-reissue receipts. No saved graph, plan or live watch is
 * retained in the archive index. The legacy full file is removed only AFTER the
 * compact receipt and its directory entry are durable. */
final class StressClosedArchive {
    private static final int MAGIC=0x50534331,VERSION=1;
    private record Closed(String id,String reservationHash,String retryHash,String entryHash,
                          List<String> origins,Ledger ledger,String reason) {}
    private record Cached(Closed value,long weight) {}
    private final Path root;
    private final StressOriginIndex origins;
    private final long cacheLimit;
    private final LinkedHashMap<String,Cached> cache=new LinkedHashMap<>(16,.75f,true);
    private long cachedBytes,hits,misses;
    private int records;

    StressClosedArchive(Path directory,long bytesLimit)throws IOException {
        if(bytesLimit<0)throw new IllegalArgumentException("negative stress archive cache limit");
        cacheLimit=bytesLimit/2;origins=new StressOriginIndex(directory,bytesLimit-cacheLimit);
        root=directory.resolve("closed-v1");
        if(!Files.exists(root))Files.createDirectory(root);
        forceDirectory(directory);
        try(var paths=Files.walk(root)){
            var it=paths.filter(p->p.getFileName().toString().endsWith(".psc")).iterator();
            while(it.hasNext()){
                Path p=it.next();long size=Files.size(p);
                if(size<16||size>MAX_RECORD_BYTES)throw new IOException("invalid stress archive size");
                Closed record=decode(Files.readAllBytes(p));
                if(!p.equals(path(record.id())))
                    throw new IOException("stress archive filename/id conflict");
                records=Math.incrementExact(records);
                origins.append(record.origins(),record.id());
            }
        }
    }
    private Closed lookup(String id)throws IOException {
        Cached cached=cache.get(id);if(cached!=null){hits++;return cached.value;}
        misses++;Path p=path(id);long size;
        try{size=Files.size(p);}catch(NoSuchFileException missing){
            if(origins.containsCredit(id))throw new IOException("known stress archive receipt missing",missing);
            return null;
        }
        if(size<16||size>MAX_RECORD_BYTES)throw new IOException("invalid stress archive size");
        Closed c=decode(Files.readAllBytes(p));
        if(!c.id.equals(id))throw new IOException("stress archive identity mismatch");
        remember(c);return c;
    }
    private void remember(Closed c){
        // Conservative accounting of retained strings, list elements, ledger
        // and index entry; this is a working-set estimate, not JVM heap size.
        long weight=1024L+2L*(c.id.length()+c.reservationHash.length()+c.retryHash.length()+c.entryHash.length()+c.reason.length());
        for(String origin:c.origins)weight+=128L+2L*origin.length();
        if(weight>cacheLimit)return;
        while(!cache.isEmpty()&&cachedBytes+weight>cacheLimit){var it=cache.values().iterator();cachedBytes-=it.next().weight;it.remove();}
        Cached prior=cache.put(c.id,new Cached(c,weight));if(prior!=null)cachedBytes-=prior.weight;cachedBytes+=weight;
    }
    boolean contains(String id)throws IOException{return lookup(id)!=null;}
    int size(){return records;}
    Ledger ledger(String id)throws IOException {Closed c=lookup(id);return c==null?null:c.ledger();}
    String owner(String origin)throws IOException{return origins.owner(origin);}
    long cachedBytes(){return cachedBytes+origins.cachedBytes();}
    String status(){return "closedCacheEntries="+cache.size()+" closedCacheBytes="+cachedBytes+" closedHits="+hits+" closedMisses="+misses+" "+origins.status();}
    boolean matchesReservation(Reservation r,boolean retry)throws IOException {
        Closed c=lookup(r.creditId());
        return c!=null&&(retry?c.retryHash():c.reservationHash()).equals(reservationHash(r,retry));
    }
    boolean matchesEntry(Entry e)throws IOException {
        Closed c=lookup(e.reservation().creditId());
        return c!=null&&c.entryHash().equals(hash(encodeCanonical(e)));
    }
    void confirmDurable(String id)throws IOException {
        // A previous process may have stopped between rename and directory
        // fsync. Reconfirm both directory entries before deleting its full file.
        forceDirectory(root.getParent());forceDirectory(root);forceDirectory(path(id).getParent());
    }
    private Path path(String id){String h=hash(id.getBytes(StandardCharsets.UTF_8));return root.resolve(h.substring(0,2)).resolve(h+".psc");}
    private static String reservationHash(Reservation r,boolean retry)throws IOException {
        // Runtime retries have the same immutable sources/settings/generation but
        // may occur on a later tick or after a configuration reload. Never renew TTL.
        if(retry)r=new Reservation(r.creditId(),r.materialGeneration(),0,Policy.defaults(),r.settings(),r.seeds(),r.origins());
        return hash(encodeCanonical(new Entry(r,Phase.RESERVED,List.of(),null,"RESERVED")));
    }
    void store(Entry e)throws IOException {
        Reservation r=e.reservation();
        var origins=r.origins().stream().map(o->o.applyId()).toList();
        Closed c=new Closed(r.creditId(),reservationHash(r,false),reservationHash(r,true),hash(encodeCanonical(e)),origins,e.ledger(),e.reason());
        Path target=path(c.id()),part=target.getParent();
        if(!Files.exists(part)){Files.createDirectory(part);forceDirectory(root);}
        if(Files.exists(target))throw new IOException("unexpected existing stress archive receipt");
        byte[] data=encodeClosed(c);Path temp=Files.createTempFile(part,"pending-",".tmp");
        try{
            try(FileChannel ch=FileChannel.open(temp,StandardOpenOption.WRITE)){
                ByteBuffer b=ByteBuffer.wrap(data);while(b.hasRemaining())ch.write(b);ch.force(true);
            }
            Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE);forceDirectory(part);
            // The sidecar cannot become a source of authority: if updating it
            // fails, the caller faults and preserves the full active file.
            this.origins.append(c.origins(),c.id());records=Math.incrementExact(records);remember(c);
        }finally{Files.deleteIfExists(temp);}
    }
    private static byte[] encodeClosed(Closed c)throws IOException {
        var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeUTF(c.id());out.writeUTF(c.reservationHash());out.writeUTF(c.retryHash());out.writeUTF(c.entryHash());
            out.writeInt(c.origins().size());for(String origin:c.origins())out.writeUTF(origin);
            var l=c.ledger();out.writeDouble(l.reservedQ());out.writeDouble(l.absorbedQ());out.writeDouble(l.directQ());out.writeDouble(l.plasticUnusedQ());out.writeDouble(l.unusedQ());out.writeInt(l.uniqueNodes());
            out.writeInt(l.reasons().size());for(var reason:Unused.values())if(l.reasons().containsKey(reason)){out.writeByte(reason.ordinal());out.writeDouble(l.reasons().get(reason));}
            out.writeUTF(c.reason());
        }
        byte[] body=bytes.toByteArray();CRC32C crc=new CRC32C();crc.update(body);
        var framed=new ByteArrayOutputStream();try(var out=new DataOutputStream(framed)){out.writeInt(body.length);out.write(body);out.writeInt((int)crc.getValue());}
        if(framed.size()>MAX_RECORD_BYTES)throw new IOException("stress archive record too large");return framed.toByteArray();
    }
    private static Closed decode(byte[] data)throws IOException {
        try(var frame=new DataInputStream(new ByteArrayInputStream(data))){
            int length=frame.readInt();if(length!=data.length-8)throw new IOException("stress archive frame size");
            byte[] body=frame.readNBytes(length);CRC32C crc=new CRC32C();crc.update(body);
            if(frame.readInt()!=(int)crc.getValue())throw new IOException("stress archive CRC mismatch");
            try(var in=new DataInputStream(new ByteArrayInputStream(body))){
                if(in.readInt()!=MAGIC||in.readInt()!=VERSION)throw new IOException("stress archive schema");
                String id=in.readUTF(),reservation=in.readUTF(),retry=in.readUTF(),entry=in.readUTF();
                if(id.isBlank()||id.length()>4096||!digest(reservation)||!digest(retry)||!digest(entry))throw new IOException("stress archive identity");
                int n=in.readInt();if(n<0||n>MAX_ROOTS)throw new IOException("stress archive origin count");
                var origins=new ArrayList<String>();var seen=new HashSet<String>();
                for(int i=0;i<n;i++){String origin=in.readUTF();if(origin.isBlank()||!seen.add(origin))throw new IOException("stress archive origins");origins.add(origin);}
                double q=in.readDouble(),a=in.readDouble(),d=in.readDouble(),p=in.readDouble(),u=in.readDouble();int nodes=in.readInt();
                n=in.readInt();if(n<0||n>Unused.values().length||nodes<0||nodes>4096)throw new IOException("stress archive ledger count");
                Map<Unused,Double> reasons=new EnumMap<>(Unused.class);double total=0;
                for(int i=0;i<n;i++){int type=in.readUnsignedByte();double amount=in.readDouble();if(type>=Unused.values().length||!valid(amount)||reasons.put(Unused.values()[type],amount)!=null)throw new IOException("stress archive unused reason");total+=amount;}
                String reason=in.readUTF();Ledger l=new Ledger(q,a,d,p,u,0,nodes,true,reasons);
                if(!valid(q)||!valid(a)||!valid(d)||!valid(p)||!valid(u)||!near(l.accountedQ(),q)||!near(total,u)||reason.isBlank()||reason.length()>4096||in.available()!=0)
                    throw new IOException("stress archive ledger imbalance");
                return new Closed(id,reservation,retry,entry,List.copyOf(origins),l,reason);
            }
        }
    }
    private static boolean digest(String value){return value.matches("[0-9a-f]{64}");}
    private static boolean valid(double v){return Double.isFinite(v)&&v>=0;}
    private static boolean near(double a,double b){return Double.isFinite(a)&&Math.abs(a-b)<=Math.max(1e-8,Math.abs(b)*1e-10);}
}
