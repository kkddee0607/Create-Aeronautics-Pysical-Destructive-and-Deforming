package com.physicaldestroydeform.stress;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.CRC32C;

/** Disposable exact source-owner index. The closed-v1 receipts, not this
 * append-only sidecar, are authoritative. Rebuilt on open; appended only
 * after durable closed-receipt publication. Compact hash candidates avoid
 * cold negative I/O; every positive candidate's full origin ID is checked. */
final class StressOriginIndex {
    private static final int SEGMENTS=256,MAX_FRAME=2*(65535+2);
    private record Binding(String origin,String credit) {}
    private record Cached(String credit,long weight) {}
    private final Path data;
    private final long cacheLimit;
    private final Candidates[] segments=new Candidates[SEGMENTS];
    private final LinkedHashMap<String,Cached> cache=new LinkedHashMap<>(16,.75f,true);
    private long length,cachedBytes,hits,misses,negativeLookups,diskLookups;

    StressOriginIndex(Path directory,long cacheLimit)throws IOException {
        this.cacheLimit=cacheLimit;Path root=directory.resolve("origin-index-v1");Files.createDirectories(root);
        data=root.resolve("owners.psi");
        // Rebuilding touches only our exact derived path. Original .psc
        // files and the active journal are never altered.
        Files.deleteIfExists(data);
    }
    private static long hash(String origin){
        try{return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(origin.getBytes(StandardCharsets.UTF_8))).getLong();}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static int segment(long hash){return (int)(hash>>>56);}
    private static int crc(byte[] bytes){CRC32C crc=new CRC32C();crc.update(bytes);return (int)crc.getValue();}
    private static int write(DataOutputStream out,String origin,String credit)throws IOException {
        var payload=new ByteArrayOutputStream();try(var body=new DataOutputStream(payload)){body.writeUTF(origin);body.writeUTF(credit);}
        byte[] bytes=payload.toByteArray();out.writeInt(bytes.length);out.write(bytes);out.writeInt(crc(bytes));return bytes.length+8;
    }
    private static Binding read(RandomAccessFile in,long offset)throws IOException {
        in.seek(offset);int length=in.readInt();if(length<6||length>MAX_FRAME||length+4L>in.length()-in.getFilePointer())throw new IOException("stress origin index frame size");
        byte[] payload=new byte[length];in.readFully(payload);
        if(in.readInt()!=crc(payload))throw new IOException("stress origin index CRC");
        try(var body=new DataInputStream(new ByteArrayInputStream(payload))){
            String origin=body.readUTF(),credit=body.readUTF();
            if(origin.isBlank()||credit.isBlank()||body.available()!=0)throw new IOException("stress origin index identity");
            return new Binding(origin,credit);
        }
    }
    private RandomAccessFile open()throws IOException {
        var file=new RandomAccessFile(data.toFile(),"r");
        try{if(file.length()!=length)throw new IOException("stress origin index length mismatch");return file;}
        catch(IOException failure){file.close();throw failure;}
    }
    void append(List<String> origins,String credit)throws IOException {
        // Retain exact credit candidates as well, so a missing .psc cannot
        // silently appear unissued even when the original receipt had no origins.
        var keys=new ArrayList<String>(origins.size()+1);
        keys.add("c:"+credit);for(String origin:origins)keys.add("o:"+origin);
        // The caller passes the verified, unique origin list of one .psc.
        // Check all previous receipts first; no prior index data is rewritten.
        long[] hashes=new long[keys.size()];
        for(int i=0;i<keys.size();i++){
            String key=keys.get(i);long hash=hash(key);hashes[i]=hash;
            if(lookup(key,hash)!=null)throw new IOException("archived credit or origin reserved twice");
        }
        long offset=length;
        if(offset!=0&&Files.size(data)!=offset)throw new IOException("stress origin index append length mismatch");
        try(var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(data,StandardOpenOption.CREATE,StandardOpenOption.APPEND)))){
            for(int i=0;i<keys.size();i++){
                long hash=hashes[i];int s=segment(hash);
                if(segments[s]==null)segments[s]=new Candidates();
                segments[s].add(hash,offset);
                offset=Math.addExact(offset,write(out,keys.get(i),credit));
            }
        }
        if(Files.size(data)!=offset)throw new IOException("stress origin index append length mismatch");
        length=offset;
    }
    String owner(String origin)throws IOException{return find("o:"+origin);}
    boolean containsCredit(String credit)throws IOException{return find("c:"+credit)!=null;}
    private String find(String origin)throws IOException {
        Cached existing=cache.get(origin);
        if(existing!=null){hits++;return existing.credit;}
        misses++;String credit=lookup(origin,hash(origin));
        if(credit==null)return null;
        long weight=256L+2L*(origin.length()+credit.length());
        if(weight<=cacheLimit){
            while(!cache.isEmpty()&&cachedBytes+weight>cacheLimit){var it=cache.values().iterator();cachedBytes-=it.next().weight;it.remove();}
            cache.put(origin,new Cached(credit,weight));cachedBytes+=weight;
        }
        return credit;
    }
    private String lookup(String origin,long hash)throws IOException {
        Candidates table=segments[segment(hash)];
        if(table==null){negativeLookups++;return null;}
        int i=table.slot(hash);String found=null;RandomAccessFile file=null;
        try{
            while(table.offsets[i]!=0){
                if(table.hashes[i]==hash){
                    if(file==null){file=open();diskLookups++;}
                    Binding value=read(file,table.offsets[i]-1);
                    if(hash(value.origin)!=hash)throw new IOException("stress origin index candidate identity mismatch");
                    if(value.origin.equals(origin)){
                        if(found!=null)throw new IOException("archived origin reserved twice");
                        found=value.credit;
                    }
                    // A truncated hash collision is only a candidate, never
                    // proof of ownership and never a false negative.
                }
                i=(i+1)&(table.offsets.length-1);
            }
        }finally{if(file!=null)file.close();}
        if(file==null)negativeLookups++;
        return found;
    }
    /** One 16-byte primitive slot instead of retaining origin strings and
     * boxed HashMap entries. Segmented growth avoids rehashing all history. */
    private static final class Candidates {
        private long[] hashes=new long[16],offsets=new long[16];
        private int size;
        private int slot(long hash){return (int)(hash^(hash>>>32))&(offsets.length-1);}
        private void add(long hash,long offset){
            if((long)(size+1)*3>offsets.length*2L)grow();
            put(hash,Math.addExact(offset,1));size++;
        }
        private void put(long hash,long encodedOffset){
            int i=slot(hash);while(offsets[i]!=0)i=(i+1)&(offsets.length-1);
            hashes[i]=hash;offsets[i]=encodedOffset;
        }
        private void grow(){
            long[] oldHashes=hashes,oldOffsets=offsets;int n=Math.multiplyExact(offsets.length,2);
            hashes=new long[n];offsets=new long[n];
            for(int i=0;i<oldOffsets.length;i++)if(oldOffsets[i]!=0)put(oldHashes[i],oldOffsets[i]);
        }
    }
    long cachedBytes(){return cachedBytes;}
    long indexBytes(){long bytes=0;for(Candidates s:segments)if(s!=null)bytes+=16L*s.offsets.length;return bytes;}
    String status(){return "originCacheEntries="+cache.size()+" originCacheBytes="+cachedBytes+" originIndexArrayBytes="+indexBytes()+" originHits="+hits+" originMisses="+misses+" originNegative="+negativeLookups+" originDiskLookups="+diskLookups;}
}
