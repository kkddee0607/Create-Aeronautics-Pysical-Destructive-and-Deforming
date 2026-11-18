package com.physicaldestroydeform.material;

import com.physicaldestroydeform.MaterialProperties;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.fml.ModList;
import java.lang.reflect.Method;
import java.util.*;

/** Optional, read-only adapter for the hash-verified Copycats+ 3.0.11 API.
 * No direct Copycats type linkage, no NBT serialization, no material setters. */
public final class CopycatMaterial {
    private CopycatMaterial(){}
    private static final String PREFIX="com.copycatsplus.copycats.foundation.copycat.";
    public static final String POLICY="CC-3.0.11/PDD-SHAPE-2";
    private static final String VERSION="3.0.11+mc.1.21.1-neoforge";
    private static final Set<String> DECORATIVE=Set.of("copycat_block","copycat_slab","copycat_byte","copycat_layer",
            "copycat_board","copycat_beam","copycat_byte_panel","copycat_corner_slice","copycat_fence","copycat_flat_pane",
            "copycat_half_layer","copycat_half_panel","copycat_ladder","copycat_pane","copycat_slice","copycat_slope",
            "copycat_slope_layer","copycat_stacked_half_layer","copycat_stairs","copycat_vertical_half_layer",
            "copycat_vertical_slice","copycat_vertical_slope","copycat_vertical_stairs","copycat_vertical_step","copycat_wall");
    public record Part(MaterialProperties material,double weight){
        public Part {Objects.requireNonNull(material);if(!Double.isFinite(weight)||weight<=0||weight>1)throw new IllegalArgumentException("invalid part volume");}
    }
    public record Resolved(MaterialProperties material,String revision,boolean safeDecorativeBE){
        public Resolved{Objects.requireNonNull(material);Objects.requireNonNull(revision);}
    }
    /** Diagnostic values are not part of identity or the material registry. */
    record Capture(Resolved resolved,String reason,String slots,double k){}
    public static MaterialProperties mix(List<Part> parts){
        double h=0,d=0,e=0,t=0,k=0;
        for(var part:parts){var m=part.material();double w=part.weight();k+=w;h+=w*m.hardness();d+=w*m.ductilityPercent();e+=w*m.elasticityPercent();t+=w*m.threshold();}
        if(k<=0||k>1+1e-10||!Double.isFinite(k))throw new IllegalArgumentException("invalid union volume");
        return new MaterialProperties((int)Math.round(h/k),Math.max(1,Math.min(1024,(int)Math.round(t))),
                (int)Math.round(d/k),(int)Math.round(e/k),false);
    }
    public static String fingerprint(BlockState state){return BuiltInRegistries.BLOCK.getKey(state.getBlock())+"|"+state;}
    public static boolean isCopycat(String fingerprint){return fingerprint!=null&&fingerprint.startsWith("copycats:");}
    private record Api(Class<?> single,Class<?> multi,Class<?> singleBlock,Class<?> multiBlock,Method getMaterial,Method storage,Method map,Method properties,Method exists){}
    private static volatile Api api;
    public static String compatibilityStatus(){
        var mod=ModList.get().getModContainerById("copycats");
        if(mod.isEmpty())return "ABSENT_OPTIONAL";
        String installed=mod.get().getModInfo().getVersion().toString();
        return (VERSION.equals(installed)?"SUPPORTED:":"UNSUPPORTED:")+installed;
    }
    static boolean supportedVersion(){
        var mod=ModList.get().getModContainerById("copycats");
        return mod.isPresent()&&VERSION.equals(mod.get().getModInfo().getVersion().toString());
    }
    static boolean safeDecorative(String id,String beClass){
        return DECORATIVE.contains(id)&&(beClass.equals(PREFIX+"CCCopycatBlockEntity")||beClass.equals(PREFIX+"multistate.MultiStateCopycatBlockEntity"));
    }
    static boolean safeDecorative(BlockState state,BlockEntity be){
        return be!=null&&isCopycat(fingerprint(state))&&supportedVersion()&&be.getBlockState().equals(state)
                &&safeDecorative(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath(),be.getClass().getName());
    }
    private static Api api(BlockEntity be)throws ReflectiveOperationException{
        Api a=api;if(a!=null)return a;
        if(!supportedVersion())throw new IllegalStateException("unsupported Copycats+ version");
        ClassLoader loader=be.getClass().getClassLoader();
        var single=Class.forName(PREFIX+"ICopycatBlockEntity",false,loader);
        var multi=Class.forName(PREFIX+"multistate.IMultiStateCopycatBlockEntity",false,loader);
        var singleBlock=Class.forName(PREFIX+"ICopycatBlock",false,loader);
        var block=Class.forName(PREFIX+"multistate.IMultiStateCopycatBlock",false,loader);
        var storage=Class.forName(PREFIX+"multistate.MaterialItemStorage",false,loader);
        a=new Api(single,multi,singleBlock,block,single.getMethod("getMaterial"),multi.getMethod("getMaterialItemStorage"),
                storage.getMethod("getMaterialMap"),block.getMethod("storageProperties"),block.getMethod("partExists",BlockState.class,String.class));
        api=a;return a;
    }
    /** Null = unavailable/unsupported, never a fallback to the copycat base definition. */
    public static Resolved capture(MaterialRegistry.Snapshot snapshot,BlockGetter level,BlockPos pos,BlockState state){
        return captureDetailed(snapshot,level,pos,state).resolved();
    }
    private static Capture rejected(String reason){return new Capture(null,reason,"",0);}
    static Capture captureDetailed(MaterialRegistry.Snapshot snapshot,BlockGetter level,BlockPos pos,BlockState state){
        String fp=fingerprint(state);
        if(!isCopycat(fp)){
            var m=snapshot.resolveFingerprint(fp);return m==null?rejected("UNRESOLVED"):new Capture(new Resolved(m,"",false),"OK","",1);
        }
        try{
            if(!supportedVersion())return rejected("UNSUPPORTED_VERSION");
            String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
            if(id.equals("copycat_ghost_block")||state.getCollisionShape(level,pos).isEmpty())return rejected("NON_SOLID");
            BlockEntity be=level.getBlockEntity(pos);if(be==null)return rejected("BE_MISSING");
            if(!be.getBlockState().equals(state))return rejected("BE_STATE_MISMATCH");
            Api a=api(be);if(!a.single().isInstance(be))return rejected("UNSUPPORTED_BE");
            List<Part> parts=new ArrayList<>();StringBuilder revision=new StringBuilder(POLICY).append('|').append(fp);
            if(a.multi().isInstance(be)){
                if(!a.multiBlock().isInstance(state.getBlock()))return rejected("MULTI_BLOCK_MISMATCH");
                var stored=(Map<?,?>)a.map().invoke(a.storage().invoke(be));
                var slots=(Set<?>)a.properties().invoke(state.getBlock());
                if(slots==null||stored==null||slots.isEmpty()||slots.size()>32||stored.size()>32)return rejected("INVALID_SLOT_MAP");
                var active=new ArrayList<String>();
                for(Object obj:slots){if(!(obj instanceof String key))return rejected("INVALID_SLOT");if(Boolean.TRUE.equals(a.exists().invoke(state.getBlock(),state,key)))active.add(key);}
                Collections.sort(active);if(active.isEmpty())return rejected("NO_ACTIVE_SLOT");
                double[] weights=weights(id,state,level,pos,active);if(weights==null)return rejected("UNVERIFIED_SLOT_SHAPE");
                for(int i=0;i<active.size();i++){
                    String key=active.get(i);if(!(stored.get(key) instanceof BlockState m))return rejected("ACTIVE_SLOT_MISSING");
                    if(!append(snapshot,parts,revision,key,m,weights[i],a))return rejected("INVALID_SLOT_MATERIAL_OR_VOLUME");
                }
            }else{
                Object material=a.getMaterial().invoke(be);if(!(material instanceof BlockState m))return rejected("INVALID_SINGLE_MATERIAL");
                double weight=switch(id){
                    case "copycat_block"->1;
                    // Pane connections vary; use the actual union, not a mass JSON constant.
                    case "copycat_pane","copycat_flat_pane"->volume(state.getCollisionShape(level,pos));
                    case "copycat_slope","copycat_vertical_slope"->.5;
                    case "copycat_layer"->layerWeight(state);
                    default->volume(state.getCollisionShape(level,pos));
                };
                if(!append(snapshot,parts,revision,"single",m,weight,a))return rejected("INVALID_SINGLE_MATERIAL_OR_VOLUME");
            }
            var mixed=mix(parts);boolean safe=safeDecorative(id,be.getClass().getName());
            return new Capture(new Resolved(mixed,hash(revision.toString()),safe),"OK",revision.toString(),parts.stream().mapToDouble(Part::weight).sum());
        }catch(ReflectiveOperationException|RuntimeException|LinkageError invalid){return rejected("ADAPTER_"+invalid.getClass().getSimpleName());}
    }
    private static double layerWeight(BlockState state){
        for(var p:state.getProperties())if(p instanceof IntegerProperty layers&&p.getName().equals("layers")){
            return layerWeight(state.getValue(layers));
        }
        return 0;
    }
    static double layerWeight(int n){return n>=1&&n<=8?n/8.0:0;}
    static boolean referenceAllowed(String fp){
        return fp!=null&&!isCopycat(fp)&&(!fp.startsWith("create:copycat_")||fp.startsWith("create:copycat_base|"));
    }
    private static boolean append(MaterialRegistry.Snapshot snapshot,List<Part> parts,StringBuilder revision,String slot,BlockState material,double weight,Api api){
        String fp=fingerprint(material);if(!referenceAllowed(fp)||!fp.startsWith("create:copycat_base|")&&api.singleBlock().isInstance(material.getBlock()))return false;
        var m=snapshot.resolveFingerprint(fp);if(m==null||material.isAir())return false;
        parts.add(new Part(m,weight));revision.append('|').append(slot).append('=').append(fp).append('@').append(weight);return true;
    }
    private static double[] weights(String id,BlockState state,BlockGetter level,BlockPos pos,List<String> slots){
        if(id.equals("copycat_slab")||id.equals("copycat_byte")){
            return fixedWeights(slots.size(),id.equals("copycat_slab")?2:8);
        }
        // Boolean multi-face shapes: isolate each active part, then partition the
        // union's occupied voxels once. Equal ownership at shared overlap avoids
        // double counting or dependence on slot iteration order.
        Map<String,Property<?>> properties=new HashMap<>();
        for(var p:state.getProperties())if(p instanceof BooleanProperty
                ||p instanceof IntegerProperty i&&i.getPossibleValues().contains(0))properties.put(p.getName(),p);
        if(!properties.keySet().containsAll(slots))return null;
        BitSet[] shapes=new BitSet[slots.size()];
        for(int i=0;i<slots.size();i++){
            BlockState isolated=state;
            for(String slot:slots)if(!slot.equals(slots.get(i))){
                var p=properties.get(slot);
                if(p instanceof BooleanProperty b)isolated=isolated.setValue(b,false);
                else if(p instanceof IntegerProperty n)isolated=isolated.setValue(n,0);
                else return null;
            }
            shapes[i]=voxels(isolated.getCollisionShape(level,pos));if(shapes[i].isEmpty())return null;
        }
        return unionWeights(shapes,voxels(state.getCollisionShape(level,pos)),GRID_VOLUME);
    }
    static double[] fixedWeights(int count,int cap){
        if(count<=0||cap<=0||count>cap)return null;
        double[] w=new double[count];Arrays.fill(w,1.0/cap);return w;
    }
    static double[] unionWeights(BitSet[] shapes,BitSet expected,int total){
        if(total<=0||shapes.length==0)return null;
        BitSet union=new BitSet();
        for(BitSet bits:shapes){if(bits==null||bits.isEmpty()||bits.length()>total)return null;union.or(bits);}
        if(!union.equals(expected))return null;
        double[] w=new double[shapes.length];
        for(int v=union.nextSetBit(0);v>=0;v=union.nextSetBit(v+1)){
            int count=0;for(BitSet bits:shapes)if(bits.get(v))count++;
            for(int i=0;i<shapes.length;i++)if(shapes[i].get(v))w[i]+=1.0/count/total;
        }
        return w;
    }
    private static final int GRID=32,GRID_VOLUME=GRID*GRID*GRID;
    private static double volume(VoxelShape shape){return voxels(shape).cardinality()/(double)GRID_VOLUME;}
    private static BitSet voxels(VoxelShape shape){
        BitSet bits=new BitSet(GRID_VOLUME);var boxes=shape.toAabbs();if(boxes.size()>128)throw new IllegalArgumentException("shape cap");
        for(var b:boxes){int x0=grid(b.minX),x1=grid(b.maxX),y0=grid(b.minY),y1=grid(b.maxY),z0=grid(b.minZ),z1=grid(b.maxZ);
            for(int x=x0;x<x1;x++)for(int y=y0;y<y1;y++)bits.set((x*GRID+y)*GRID+z0,(x*GRID+y)*GRID+z1);
        }return bits;
    }
    // Fence/wall collision can extend beyond its own block. Count only the owned unit cube.
    private static int grid(double position){if(!Double.isFinite(position))throw new IllegalArgumentException("non-finite shape");double scaled=Math.max(0,Math.min(1,position))*GRID;int i=(int)Math.round(scaled);
        if(!Double.isFinite(position)||i<0||i>GRID||Math.abs(scaled-i)>1e-8)throw new IllegalArgumentException("unverified shape grid");return i;}
    private static String hash(String value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
