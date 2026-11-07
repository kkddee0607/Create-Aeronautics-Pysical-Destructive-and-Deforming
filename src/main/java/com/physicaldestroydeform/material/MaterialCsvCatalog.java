package com.physicaldestroydeform.material;

import com.physicaldestroydeform.MaterialProperties;
import java.io.IOException;
import java.io.Reader;
import java.util.*;
import java.util.function.Predicate;

/** Immutable vanilla-material CSV snapshot. Parser has no Minecraft/registry side effects.
 * The caller must filter against the actual registered IDs at rebuild and swap only a complete snapshot.
 */
public final class MaterialCsvCatalog {
    public static final int MAX_ROWS=50000, MAX_CELL=4096, MAX_CHARS=8_000_000;
    public static final String SCHEMA="PDD-MATERIAL-0.4.1/CSV-v1";
    public record Block(int h,int t,int d,int e,boolean unbreakable,boolean eligible,
                        String basis,String confidence,String source) {
        public Block {require(h,0,255);require(t,1,255);require(d,0,100);require(e,0,100);}
        public MaterialProperties toProperties(MaterialProperties inferred) {
            // An external data error cannot override Minecraft's intrinsic unbreakability.
            return new MaterialProperties(h,t,d,e,unbreakable||inferred.unbreakable());
        }
    }
    public record Item(int h,int d,int e,String correspondingBlock,String basis,
                       String confidence,String source) {
        public Item {require(h,0,255);require(d,0,100);require(e,0,100);}
    }
    public record Snapshot(long generation,Map<String,Block> blocks,Map<String,Item> items,
                           String sourceIdentity,int skippedUnregistered,int rejectedDuplicates) {
        public Snapshot {blocks=Map.copyOf(blocks);items=Map.copyOf(items);}
    }
    /** Field-by-field CSV/user overlay. null means unspecified, never confuse with valid 0. */
    public record PartialBlock(Integer h,Integer t,Integer d,Integer e,Boolean unbreakable,
                               String source) {
        public PartialBlock {
            check(h,0,255);check(t,1,255);check(d,0,100);check(e,0,100);
        }
        public static PartialBlock from(Block b) {
            return new PartialBlock(b.h,b.t,b.d,b.e,b.unbreakable,b.source);
        }
        public PartialBlock overlay(PartialBlock high) {
            if(high==null)return this;
            return new PartialBlock(first(high.h,h),first(high.t,t),first(high.d,d),first(high.e,e),
                    first(high.unbreakable,unbreakable),high.source==null?source:high.source);
        }
        public MaterialProperties resolve(MaterialProperties base) {
            int resolvedH=first(h,base.hardness());
            int resolvedT=first(t,base.threshold());
            int resolvedD=first(d,base.ductilityPercent());
            int resolvedE=first(e,base.elasticityPercent());
            return new MaterialProperties(resolvedH,resolvedT,resolvedD,resolvedE,
                    base.unbreakable()||Boolean.TRUE.equals(unbreakable),
                    resolvedD==base.ductilityPercent()?base.yieldThreshold():MaterialProperties.defaultYield(resolvedD));
        }
    }
    private MaterialCsvCatalog(){}
    private static <T> T first(T a,T b){return a==null?b:a;}
    private static void require(int value,int min,int max){
        if(value<min||value>max)throw new IllegalArgumentException("value outside "+min+".."+max+": "+value);
    }
    private static void check(Integer v,int min,int max){if(v!=null)require(v,min,max);}

    /** Every source is validated before the caller atomically publishes this snapshot. */
    public static Snapshot parse(Reader blockReader,Reader itemReader,Predicate<String> registeredBlocks,
                                 Predicate<String> registeredItems,long generation,
                                 String sourceIdentity) throws IOException {
        Objects.requireNonNull(registeredBlocks);Objects.requireNonNull(registeredItems);
        Table blocks=read(blockReader,Set.of("namespace","id","H","T","D","E","unbreakable","eligibility"));
        Table items=read(itemReader,Set.of("namespace","id","H","D","E","corresponding_block_id"));
        Map<String,Block> loadedBlocks=new LinkedHashMap<>();
        Map<String,Item> loadedItems=new LinkedHashMap<>();
        Set<String> bannedBlocks=new HashSet<>(),bannedItems=new HashSet<>();
        int[] ignored={0},duplicates={0};
        for(var row:blocks.rows) {
            String id=id(row,"id","namespace");
            if(!registeredBlocks.test(id)){ignored[0]++;continue;}
            if(bannedBlocks.contains(id))continue;
            if(loadedBlocks.containsKey(id)) {
                loadedBlocks.remove(id);bannedBlocks.add(id);duplicates[0]++;continue;
            }
            // Reject an invalid row rather than permitting accidental weak or strong defaults.
            loadedBlocks.put(id,new Block(integer(row,"H",0,255),integer(row,"T",1,255),
                    integer(row,"D",0,100),integer(row,"E",0,100),
                    bool(row,"unbreakable"),"COLLISION_CANDIDATE".equals(row.get("eligibility")),
                    row.getOrDefault("profile_basis",""),row.getOrDefault("confidence",""),
                    row.getOrDefault("source","")));
        }
        for(var row:items.rows) {
            String id=id(row,"id","namespace");
            if(!registeredItems.test(id)){ignored[0]++;continue;}
            if(bannedItems.contains(id))continue;
            if(loadedItems.containsKey(id)){
                loadedItems.remove(id);bannedItems.add(id);duplicates[0]++;continue;
            }
            String corresponding=row.getOrDefault("corresponding_block_id","");
            if(!corresponding.isEmpty())checkId(corresponding);
            loadedItems.put(id,new Item(integer(row,"H",0,255),integer(row,"D",0,100),
                    integer(row,"E",0,100),corresponding,row.getOrDefault("profile_basis",""),
                    row.getOrDefault("confidence",""),row.getOrDefault("source","")));
        }
        return new Snapshot(generation,loadedBlocks,loadedItems,sourceIdentity,ignored[0],duplicates[0]);
    }
    private static void checkId(String id) {
        if(!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
            throw new IllegalArgumentException("invalid registered identifier: "+id);
    }
    private static String id(Map<String,String> row,String key,String namespace) {
        String id=required(row,key),ns=required(row,namespace);
        checkId(id);
        if(!id.substring(0,id.indexOf(':')).equals(ns))
            throw new IllegalArgumentException("namespace mismatch: "+id+" vs "+ns);
        return id;
    }
    private static String required(Map<String,String> row,String key) {
        String value=row.get(key);
        if(value==null||value.isEmpty())throw new IllegalArgumentException("missing "+key);
        return value;
    }
    private static int integer(Map<String,String> row,String key,int min,int max){
        String str=required(row,key);
        if(!str.matches("[0-9]{1,3}"))throw new IllegalArgumentException("invalid "+key+"="+str);
        int number=Integer.parseInt(str);require(number,min,max);return number;
    }
    private static boolean bool(Map<String,String> row,String key) {
        String str=required(row,key);
        if(!str.equals("0")&&!str.equals("1"))throw new IllegalArgumentException("invalid boolean "+key);
        return str.equals("1");
    }
    private record Table(List<Map<String,String>> rows) {}
    private static Table read(Reader source,Set<String> necessary) throws IOException {
        Objects.requireNonNull(source);
        // RFC 4180: CSV commas, embedded quoted newlines, CRLF, escaped quotes and UTF-8 BOM.
        var reader=new java.io.PushbackReader(new java.io.BufferedReader(source),2);
        List<List<String>> records=new ArrayList<>();
        List<String> fields=new ArrayList<>();StringBuilder cell=new StringBuilder();
        boolean quoted=false,afterQuote=false,hadAny=false;
        int chars=0,c;
        while((c=reader.read())!=-1) {
            if(++chars>MAX_CHARS)throw new IOException("CSV input too large");
            if(c=='\ufeff'&&chars==1)continue;
            hadAny=true;
            if(quoted) {
                if(c=='"') {
                    int next=reader.read();
                    if(next=='"'){chars++;cell.append('"');}
                    else {quoted=false;afterQuote=true;if(next!=-1)reader.unread(next);}
                } else cell.append((char)c);
            } else if(afterQuote) {
                if(c==',') {fields.add(cell.toString());cell.setLength(0);afterQuote=false;}
                else if(c=='\n'||c=='\r') {
                    fields.add(cell.toString());cell.setLength(0);afterQuote=false;
                    records.add(List.copyOf(fields));fields.clear();hadAny=false;
                    if(c=='\r') {int next=reader.read();if(next!='\n'&&next!=-1)reader.unread(next);}
                } else throw new IOException("unexpected character after quoted field");
            } else if(c=='"') {
                if(!cell.isEmpty())throw new IOException("quoted field begins in unquoted content");
                quoted=true;
            } else if(c==',') {fields.add(cell.toString());cell.setLength(0);}
            else if(c=='\r'||c=='\n') {
                fields.add(cell.toString());cell.setLength(0);
                records.add(List.copyOf(fields));fields.clear();hadAny=false;
                if(c=='\r') {int next=reader.read();if(next!='\n'&&next!=-1)reader.unread(next);}
            } else cell.append((char)c);
            if(cell.length()>MAX_CELL)throw new IOException("CSV cell too large");
            if(records.size()>MAX_ROWS+1)throw new IOException("too many records");
        }
        if(quoted)throw new IOException("unterminated quoted CSV field");
        if(hadAny||!fields.isEmpty()||!cell.isEmpty()||afterQuote) {
            fields.add(cell.toString());records.add(List.copyOf(fields));
        }
        if(records.isEmpty())throw new IOException("CSV has no header");
        List<String> header=records.getFirst();
        if(new HashSet<>(header).size()!=header.size())throw new IOException("duplicate header");
        if(!header.containsAll(necessary))throw new IOException("missing columns: "+necessary);
        List<Map<String,String>> data=new ArrayList<>();
        for(int i=1;i<records.size();i++) {
            var row=records.get(i);
            if(row.size()!=header.size())throw new IOException("ragged CSV line "+(i+1));
            Map<String,String> map=new HashMap<>();
            for(int j=0;j<header.size();j++)map.put(header.get(j),row.get(j));
            data.add(map);
        }
        return new Table(data);
    }

    /** Missing CSV cells remain null. A malformed row is rejected in isolation;
     * malformed CSV structure/header rejects the entire source. */
    public record PartialItem(Integer h,Integer d,Integer e,String correspondingBlock,String source) {
        public PartialItem { check(h,0,255);check(d,0,100);check(e,0,100);
            if(correspondingBlock!=null&&!correspondingBlock.isBlank())checkId(correspondingBlock); }
        public Item resolve(Item base) {
            return new Item(first(h,base.h()),first(d,base.d()),first(e,base.e()),
                    first(correspondingBlock,base.correspondingBlock()),base.basis(),base.confidence(),
                    source==null?base.source():source);
        }
    }
    public record Layer(Map<String,PartialBlock> blocks,Map<String,PartialItem> items,
                        int duplicateIds,int invalidRows,int unregisteredIds) {
        public Layer { blocks=Map.copyOf(blocks);items=Map.copyOf(items); }
    }
    public static Layer parseUser(Reader blocks,Reader items,Predicate<String> blockRegistered,
                                  Predicate<String> itemRegistered) throws IOException {
        Table br=blocks==null?null:read(blocks,Set.of("id"));
        Table ir=items==null?null:read(items,Set.of("id"));
        Map<String,PartialBlock> outB=new HashMap<>();Map<String,PartialItem> outI=new HashMap<>();
        Set<String> bannedB=new HashSet<>(),bannedI=new HashSet<>();
        int duplicate=0,invalid=0,unregistered=0;
        if(br!=null)for(var r:br.rows) {
            String id;
            try {id=idUser(r);}
            catch(RuntimeException bad){invalid++;continue;}
            if(!blockRegistered.test(id)){unregistered++;continue;}
            if(bannedB.contains(id))continue;
            if(outB.containsKey(id)) {outB.remove(id);bannedB.add(id);duplicate++;continue;}
            try {outB.put(id,new PartialBlock(optional(r,"H",0,255),optional(r,"T",1,255),
                    optional(r,"D",0,100),optional(r,"E",0,100),optionalBool(r,"unbreakable"),"external-csv"));}
            catch(RuntimeException bad){invalid++;}
        }
        if(ir!=null)for(var r:ir.rows) {
            String id;
            try {id=idUser(r);}
            catch(RuntimeException bad){invalid++;continue;}
            if(!itemRegistered.test(id)){unregistered++;continue;}
            if(bannedI.contains(id))continue;
            if(outI.containsKey(id)){outI.remove(id);bannedI.add(id);duplicate++;continue;}
            try {String corresponding=r.get("corresponding_block_id");
                if(corresponding!=null&&corresponding.isBlank())corresponding=null;
                outI.put(id,new PartialItem(optional(r,"H",0,255),optional(r,"D",0,100),
                        optional(r,"E",0,100),corresponding,"external-csv"));}
            catch(RuntimeException bad){invalid++;}
        }
        return new Layer(outB,outI,duplicate,invalid,unregistered);
    }
    private static String idUser(Map<String,String> row) {
        String name=required(row,"id");checkId(name);
        String namespace=row.get("namespace");
        if(namespace!=null&&!namespace.isBlank()&&!name.startsWith(namespace+":"))
            throw new IllegalArgumentException("namespace does not match ID");
        return name;
    }
    private static Integer optional(Map<String,String> row,String name,int min,int max) {
        String value=row.get(name);
        if(value==null||value.isBlank())return null;
        if(!value.matches("[0-9]{1,3}"))throw new IllegalArgumentException("invalid integer "+name);
        int v=Integer.parseInt(value);require(v,min,max);return v;
    }
    private static Boolean optionalBool(Map<String,String> row,String name) {
        String value=row.get(name);
        if(value==null||value.isBlank())return null;
        if(!value.equals("0")&&!value.equals("1"))throw new IllegalArgumentException("invalid boolean "+name);
        return value.equals("1");
    }

}
