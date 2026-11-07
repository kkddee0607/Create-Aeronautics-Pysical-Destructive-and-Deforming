package com.physicaldestroydeform.material;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.physicaldestroydeform.MaterialProperties;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.Reader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import net.minecraft.world.item.Item;


/** IDS 1.0.6: immutable block-definition and relevant state material tables, atomically swapped on reload. */
public final class MaterialRegistry {
    public static final MaterialProperties FALLBACK=new MaterialProperties(128,20,30);
    public static final String ALGORITHM_REVISION="PDD-IDS-1.0.7/CAP-MATERIAL-STATIC-0.4.1";
    public record Snapshot(long generation,Map<ResourceLocation,MaterialProperties> definitions,
                           Map<String,MaterialProperties> stateSpecific,String algorithmRevision,
                           Map<ResourceLocation,MaterialCsvCatalog.Item> itemDefinitions,
                           Map<String,String> sourceHashes) {
        public Snapshot {
            definitions=Map.copyOf(definitions);
            stateSpecific=Map.copyOf(stateSpecific);
            itemDefinitions=Map.copyOf(itemDefinitions);
            sourceHashes=Map.copyOf(sourceHashes);
        }
        /** Previous four-argument constructor remains binary-source compatible for 0.4.0 tests. */
        public Snapshot(long generation,Map<ResourceLocation,MaterialProperties> definitions,
                        Map<String,MaterialProperties> stateSpecific,String algorithmRevision) {
            this(generation,definitions,stateSpecific,algorithmRevision,Map.of(),Map.of());
        }
        public MaterialProperties resolveFingerprint(String fingerprint) {
            if (fingerprint==null) return null;
            int separator=fingerprint.indexOf('|');
            if (separator<=0 || separator==fingerprint.length()-1) return null;
            ResourceLocation blockId=ResourceLocation.tryParse(fingerprint.substring(0,separator));
            if (blockId==null) return null;
            MaterialProperties found=definitions.get(blockId);
            if (found==null) return null;
            return stateSpecific.getOrDefault(fingerprint,found);
        }
        public MaterialCsvCatalog.Item item(ResourceLocation id){return itemDefinitions.get(id);}
    }
    private static volatile Snapshot current=new Snapshot(0,Map.of(),Map.of(),ALGORITHM_REVISION);
    private MaterialRegistry() {}
    public static Snapshot snapshot(){return current;}
    public static MaterialProperties get(Block block){return get(BuiltInRegistries.BLOCK.getKey(block));}
    public static MaterialProperties get(ResourceLocation id){return current.definitions().getOrDefault(id,FALLBACK);}
    public static MaterialProperties get(BlockState state) {
        ResourceLocation id=BuiltInRegistries.BLOCK.getKey(state.getBlock());
        Snapshot s=current;
        return s.stateSpecific().getOrDefault(id+"|"+state,s.definitions().getOrDefault(id,FALLBACK));
    }
    public static int size(){return current.definitions().size();}
    public static long generation(){return current.generation();}
    public static synchronized void clear(){current=new Snapshot(current.generation()+1,Map.of(),Map.of(),ALGORITHM_REVISION);}

    /** Recompute only registered block definitions, never iterate placed World/Plot blocks. */
    public static synchronized void rebuild(MinecraftServer server,Logger logger) {
        // Stage every source before publishing the new generation. Bad external CSV never
        // replaces a previously valid snapshot with a partial or empty material table.
        final MaterialSources source;
        try {source=readMaterialSources(logger);}
        catch(Exception malformed) {
            logger.error("[PDD-MATERIAL] reason=INVALID_SOURCE snapshotKept={} details={}",
                    !current.definitions().isEmpty(),malformed.toString(),malformed);
            if(!current.definitions().isEmpty())return;
            try {source=readMaterialSourcesBundledOnly(logger);}
            catch(Exception bundledFailure) {
                logger.error("[PDD-MATERIAL] reason=BUNDLED_SOURCE_UNREADABLE fallback=INFERENCE",bundledFailure);
                return; // fail closed: avoid changing gameplay material generation on broken distribution
            }
        }
        Map<ResourceLocation,MaterialCsvCatalog.Item> items=itemTable(source,logger);
        Map<ResourceLocation,MaterialProperties> inferred=new HashMap<>();
        Map<ResourceLocation,Double> defaultSpeeds=new HashMap<>();
        int inaccessibleMiningSpeeds=0;
        for(Block block:BuiltInRegistries.BLOCK) {
            ResourceLocation id=BuiltInRegistries.BLOCK.getKey(block);
            double speed;
            try { speed=block.defaultBlockState().getDestroySpeed(EmptyBlockGetter.INSTANCE,BlockPos.ZERO); }
            catch(RuntimeException e) {speed=Double.NaN;inaccessibleMiningSpeeds++;}
            double explosion=block.getExplosionResistance();
            int h=HardnessScale.fromMinecraft(speed,explosion);
            inferred.put(id,new MaterialProperties(h,MaterialProperties.defaultThreshold(h),
                    FALLBACK.ductilityPercent(),FALLBACK.elasticityPercent(),Double.isFinite(speed)&&speed<0));
            defaultSpeeds.put(id,speed);
        }
        Map<ResourceLocation,MaterialProperties> result=new HashMap<>(inferred);
        int recipesInferred=inferFromRecipes(server,inferred,result,items);
        int profiles=applyExplicitProfiles(result);
        // States that actually differ in a world-free intrinsic value get a separate cache entry.
        // Do not infer a state property requiring an unavailable world context.
        Map<String,MaterialProperties> states=new HashMap<>();
        int stateProfiles=0;
        for(Block block:BuiltInRegistries.BLOCK) {
            ResourceLocation id=BuiltInRegistries.BLOCK.getKey(block);
            if (MaterialProfiles.explicit(id.toString()))continue; // explicit block policy outranks inferred state hardness
            MaterialProperties base=result.get(id);
            double defaultSpeed=defaultSpeeds.get(id);
            for(BlockState state:block.getStateDefinition().getPossibleStates()) {
                if(state==block.defaultBlockState())continue;
                double speed;
                try{speed=state.getDestroySpeed(EmptyBlockGetter.INSTANCE,BlockPos.ZERO);}
                catch(RuntimeException unavailable){continue;}
                if(Double.compare(speed,defaultSpeed)==0)continue;
                int h=HardnessScale.fromMinecraft(speed,block.getExplosionResistance());
                states.put(id+"|"+state,new MaterialProperties(h,MaterialProperties.defaultThreshold(h),
                        base.ductilityPercent(),base.elasticityPercent(),base.unbreakable()||(Double.isFinite(speed)&&speed<0),
                        base.yieldThreshold()));
                stateProfiles++;
            }
        }
        int bundledCount=applyBundled(source.bundled(),result,states);
        int overrides=applyOverrides(result,states,logger);
        int externalCount=applyExternal(source.external(),result,states,logger);
        Snapshot next=new Snapshot(current.generation()+1,result,states,ALGORITHM_REVISION,
                items,source.hashes());
        current=next;
        logger.info("[PDD-MATERIAL] rebuild generation={} policy={} blockTypes={} items={} recipesInferred={} " +
                "explicitProfiles={} stateProfiles={} jarBlockRows={} jsonOverrides={} externalBlockRows={} " +
                "invalidExternalRows={} duplicateExternalIds={} unregisteredExternalIds={} miningFallback={} " +
                "sources={} storage=IMMUTABLE_DEFINITION_AND_STATE",
                next.generation(),next.algorithmRevision(),result.size(),items.size(),recipesInferred,profiles,
                stateProfiles,bundledCount,overrides,externalCount,source.external().invalidRows(),
                source.external().duplicateIds(),source.external().unregisteredIds(),
                inaccessibleMiningSpeeds,next.sourceHashes());
    }
    private static int inferFromRecipes(MinecraftServer server,Map<ResourceLocation,MaterialProperties> defaults,
                                        Map<ResourceLocation,MaterialProperties> result,
                                        Map<ResourceLocation,MaterialCsvCatalog.Item> itemMaterials) {
        List<RecipeHolder<?>> recipes=new ArrayList<>(server.getRecipeManager().getRecipes());
        recipes.sort(Comparator.comparing(holder->holder.id().toString()));
        int inferred=0;
        java.util.HashSet<ResourceLocation> claimed=new java.util.HashSet<>();
        for(RecipeHolder<?> holder:recipes) {
            try {
                ItemStack output=holder.value().getResultItem(server.registryAccess());
                if(!(output.getItem() instanceof BlockItem outputBlock))continue;
                ResourceLocation outputId=BuiltInRegistries.BLOCK.getKey(outputBlock.getBlock());
                if(!defaults.containsKey(outputId)||claimed.contains(outputId))continue;
                double h=0,t=0,d=0,e=0;int slots=0;
                for(Ingredient ingredient:holder.value().getIngredients()) {
                    MaterialProperties option=averageOptions(ingredient,defaults,itemMaterials);
                    if(option==null)continue;
                    h+=option.hardness();t+=option.threshold();d+=option.ductilityPercent();e+=option.elasticityPercent();slots++;
                }
                if(slots==0)continue;
                MaterialProperties base=defaults.get(outputId);
                result.put(outputId,new MaterialProperties(blend(base.hardness(),h/slots),
                        Math.max(1,blend(base.threshold(),t/slots)),blend(base.ductilityPercent(),d/slots),
                        blend(base.elasticityPercent(),e/slots),base.unbreakable()));
                claimed.add(outputId);
                inferred++;
            }catch(RuntimeException unsupportedRecipe){ /* Unrecognized custom recipe is not evidence. */ }
        }
        return inferred;
    }
    private static MaterialProperties averageOptions(Ingredient ingredient,
                                                      Map<ResourceLocation,MaterialProperties> defaults,
                                                      Map<ResourceLocation,MaterialCsvCatalog.Item> itemMaterials) {
        if(ingredient.isEmpty())return null;
        double h=0,t=0,d=0,e=0;int count=0;
        for(ItemStack stack:ingredient.getItems()) {
            MaterialProperties value=null;
            if(stack.getItem() instanceof BlockItem blockItem)
                value=defaults.get(BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()));
            if(value==null) {
                var item=itemMaterials.get(BuiltInRegistries.ITEM.getKey(stack.getItem()));
                if(item!=null)value=new MaterialProperties(item.h(),
                        MaterialProperties.defaultThreshold(item.h()),item.d(),item.e(),false);
            }
            if(value==null)continue;
            h+=value.hardness();t+=value.threshold();d+=value.ductilityPercent();e+=value.elasticityPercent();count++;
        }
        return count==0?null:new MaterialProperties((int)Math.round(h/count),
                (int)Math.round(t/count),(int)Math.round(d/count),(int)Math.round(e/count),false);
    }
    private static int blend(double intrinsic,double ingredient){return (int)Math.round(.75*intrinsic+.25*ingredient);}

    private record MaterialSources(MaterialCsvCatalog.Snapshot bundled,
                                   MaterialCsvCatalog.Layer external,Map<String,String> hashes) {}
    private static final int MAX_SOURCE_BYTES=8_000_000;
    private static MaterialSources readMaterialSources(Logger logger) throws IOException {
        return readSources(logger,true);
    }
    private static MaterialSources readMaterialSourcesBundledOnly(Logger logger) throws IOException {
        return readSources(logger,false);
    }
    private static MaterialSources readSources(Logger logger,boolean withExternal) throws IOException {
        byte[] bb,bi;
        try(InputStream blocks=MaterialRegistry.class.getResourceAsStream("/pdd/materials/blocks.csv");
            InputStream items=MaterialRegistry.class.getResourceAsStream("/pdd/materials/items.csv")) {
            if(blocks==null || items==null)throw new IOException("bundled CSV missing");
            bb=readBounded(blocks);bi=readBounded(items);
        }
        MaterialCsvCatalog.Snapshot bundled=MaterialCsvCatalog.parse(
                new StringReader(new String(bb,StandardCharsets.UTF_8)),
                new StringReader(new String(bi,StandardCharsets.UTF_8)),
                id->registeredBlock(id),id->registeredItem(id),current.generation()+1,"jar");
        Map<String,String> hashes=new HashMap<>();
        hashes.put("jar.blocks",sha(bb));hashes.put("jar.items",sha(bi));
        MaterialCsvCatalog.Layer external=new MaterialCsvCatalog.Layer(Map.of(),Map.of(),0,0,0);
        if(withExternal) {
            Path game=FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
            Path mods=game.resolve("mods");
            Path directory=mods.resolve("pdd").resolve("materials");
            Path eb=directory.resolve("blocks.csv"), ei=directory.resolve("items.csv");
            boolean hasB=Files.exists(eb),hasI=Files.exists(ei);
            if(hasB||hasI) {
                Path modsReal=mods.toRealPath();
                for(Path candidate:List.of(eb,ei))if(Files.exists(candidate)) {
                    if(!Files.isRegularFile(candidate)||!candidate.toRealPath().startsWith(modsReal))
                        throw new IOException("external material path violates mods boundary: "+candidate);
                }
                byte[] b=hasB?readBounded(Files.newInputStream(eb)):null;
                byte[] i=hasI?readBounded(Files.newInputStream(ei)):null;
                if(b!=null)hashes.put("external.blocks",sha(b));
                if(i!=null)hashes.put("external.items",sha(i));
                external=MaterialCsvCatalog.parseUser(
                        b==null?null:new StringReader(new String(b,StandardCharsets.UTF_8)),
                        i==null?null:new StringReader(new String(i,StandardCharsets.UTF_8)),
                        id->registeredBlock(id),id->registeredItem(id));
                logger.info("[PDD-MATERIAL] source=USER_CSV blocks={} items={} duplicate={} invalid={} unregistered={}",
                        external.blocks().size(),external.items().size(),external.duplicateIds(),
                        external.invalidRows(),external.unregisteredIds());
            }
        }
        return new MaterialSources(bundled,external,Map.copyOf(hashes));
    }
    private static byte[] readBounded(InputStream in) throws IOException {
        try(in) {byte[] bytes=in.readNBytes(MAX_SOURCE_BYTES+1);
            if(bytes.length>MAX_SOURCE_BYTES)throw new IOException("material source exceeds size cap");
            return bytes;
        }
    }
    private static String sha(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static boolean registeredBlock(String id) {
        ResourceLocation resource=ResourceLocation.tryParse(id);
        return resource!=null && BuiltInRegistries.BLOCK.containsKey(resource);
    }
    private static boolean registeredItem(String id) {
        ResourceLocation resource=ResourceLocation.tryParse(id);
        return resource!=null && BuiltInRegistries.ITEM.containsKey(resource);
    }
    private static int applyBundled(MaterialCsvCatalog.Snapshot jar,
                                    Map<ResourceLocation,MaterialProperties> definitions,
                                    Map<String,MaterialProperties> states) {
        int count=0;
        for(var entry:jar.blocks().entrySet()) {
            ResourceLocation id=ResourceLocation.tryParse(entry.getKey());
            if(id==null||!definitions.containsKey(id))continue;
            var candidate=MaterialCsvCatalog.PartialBlock.from(entry.getValue());
            definitions.put(id,candidate.resolve(definitions.get(id)));
            String prefix=id+"|";
            states.replaceAll((key,old)->key.startsWith(prefix)?candidate.resolve(old):old);
            count++;
        }
        return count;
    }
    private static int applyExternal(MaterialCsvCatalog.Layer external,
                                     Map<ResourceLocation,MaterialProperties> definitions,
                                     Map<String,MaterialProperties> states,Logger log) {
        int count=0,logged=0;
        for(var entry:external.blocks().entrySet()) {
            ResourceLocation id=ResourceLocation.tryParse(entry.getKey());
            if(id==null||!definitions.containsKey(id))continue;
            var old=definitions.get(id);var user=entry.getValue();
            MaterialProperties replacement=user.resolve(old);
            if(!old.equals(replacement)&&logged++<20)log.info("[PDD-MATERIAL] source=USER_CSV reason=OVERRIDE id={} before={} after={} precedence=CSV_GT_JSON",id,old,replacement);
            definitions.put(id,replacement);
            String prefix=id+"|";
            states.replaceAll((key,value)->key.startsWith(prefix)?user.resolve(value):value);
            count++;
        }
        return count;
    }
    private static Map<ResourceLocation,MaterialCsvCatalog.Item> itemTable(MaterialSources sources,Logger log) {
        Map<ResourceLocation,MaterialCsvCatalog.Item> items=new HashMap<>();
        for(var entry:sources.bundled().items().entrySet()) {
            ResourceLocation id=ResourceLocation.tryParse(entry.getKey());
            if(id!=null)items.put(id,entry.getValue());
        }
        int changed=0;
        for(var entry:sources.external().items().entrySet()) {
            ResourceLocation id=ResourceLocation.tryParse(entry.getKey());
            if(id==null)continue;
            MaterialCsvCatalog.Item old=items.get(id);
            if(old==null)old=new MaterialCsvCatalog.Item(128,30,20,"","fallback","LOW","fallback");
            MaterialCsvCatalog.Item value=entry.getValue().resolve(old);
            if(!old.equals(value))changed++;
            items.put(id,value);
        }
        log.info("[PDD-MATERIAL] source=ITEM_CSV jarItems={} mergedItems={} externalChanges={}",
                sources.bundled().items().size(),items.size(),changed);
        return items;
    }

    private static int applyExplicitProfiles(Map<ResourceLocation,MaterialProperties> values) {
        int count=0;
        for(var entry:values.entrySet()) {
            MaterialProperties p=MaterialProfiles.profile(entry.getKey().toString(),entry.getValue());
            if(p!=null){entry.setValue(p);count++;}
        }
        return count;
    }
    private static int applyOverrides(Map<ResourceLocation,MaterialProperties> definitions,
                                      Map<String,MaterialProperties> states,Logger logger) {
        Path path=FMLPaths.CONFIGDIR.get().resolve("pdd-materials.json");
        if(!Files.isRegularFile(path))return 0;
        int count=0;
        try(Reader reader=Files.newBufferedReader(path,StandardCharsets.UTF_8)) {
            JsonObject root=JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("blocks");
            if(root==null)return 0;
            for(Map.Entry<String,JsonElement> entry:root.entrySet()) {
                try {
                    ResourceLocation id=ResourceLocation.tryParse(entry.getKey());
                    if(id==null || !definitions.containsKey(id)) {
                        logger.warn("[PDD-MATERIAL] override ignored unregistered block={}",entry.getKey());continue;
                    }
                    JsonObject override=entry.getValue().getAsJsonObject();
                    MaterialProperties original=definitions.get(id);
                    MaterialProperties replacement=override(original,override);
                    // Stage the complete per-block override. Invalid nested values must not
                    // leave a partially changed definition or stale per-state values.
                    Map<String,MaterialProperties> stagedStates=new HashMap<>();
                    for(String stateKey:states.keySet()) {
                        if(stateKey.startsWith(id+"|"))stagedStates.put(stateKey,replacement);
                    }
                    int matchedTotal=0;
                    // Only exact registered states are eligible; do not infer state selectors.
                    if(override.has("states")) {
                        JsonObject stateOverrides=override.getAsJsonObject("states");
                        Block block=BuiltInRegistries.BLOCK.get(id);
                        for(var selector:stateOverrides.entrySet()) {
                            int matched=0;
                            for(BlockState actual:block.getStateDefinition().getPossibleStates()) {
                                String descriptor=actual.toString();
                                int open=descriptor.indexOf('[');
                                String properties=open<0?"":descriptor.substring(open+1,descriptor.length()-1);
                                if(!selector.getKey().equals(descriptor) && !selector.getKey().equals(properties))continue;
                                String key=id+"|"+descriptor;
                                stagedStates.put(key,override(stagedStates.getOrDefault(key,replacement),
                                        selector.getValue().getAsJsonObject()));
                                matched++;
                            }
                            if(matched==0)logger.warn("[PDD-MATERIAL] state override unmatched block={} selector={}",
                                    id,selector.getKey());
                            matchedTotal+=matched;
                        }
                    }
                    definitions.put(id,replacement);
                    states.putAll(stagedStates);
                    count+=1+matchedTotal;
                }catch(RuntimeException invalid) {
                    logger.warn("[PDD-MATERIAL] invalid override for {}: {}",entry.getKey(),invalid.getMessage());
                }
            }
        }catch(Exception unreadable){logger.warn("[PDD-MATERIAL] override file unreadable path={}",path,unreadable);}
        return count;
    }
    private static MaterialProperties override(MaterialProperties old,JsonObject json) {
        int h=getInt(json,"hardness",old.hardness());
        int d=getInt(json,"ductilityPercent",old.ductilityPercent());
        double yield=json.has("yieldThreshold")?json.get("yieldThreshold").getAsDouble():
                old.ductilityPercent()==d?old.yieldThreshold():MaterialProperties.defaultYield(d);
        return new MaterialProperties(h,getInt(json,"threshold",old.threshold()),d,
                getInt(json,"elasticityPercent",old.elasticityPercent()),
                old.unbreakable() || (json.has("unbreakable") && json.get("unbreakable").getAsBoolean()),yield);
    }
    private static int getInt(JsonObject obj,String key,int fallback){return obj.has(key)?obj.get(key).getAsInt():fallback;}
}
