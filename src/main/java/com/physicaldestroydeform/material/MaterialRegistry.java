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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One immutable, atomically replaceable table for all registered block types. */
public final class MaterialRegistry {
    public static final MaterialProperties FALLBACK = new MaterialProperties(128, 20, 30);
    private static volatile Map<ResourceLocation, MaterialProperties> properties = Map.of();
    private static volatile long generation;

    private MaterialRegistry() {}
    public static MaterialProperties get(Block block) { return get(BuiltInRegistries.BLOCK.getKey(block)); }
    public static MaterialProperties get(ResourceLocation id) { return properties.getOrDefault(id, FALLBACK); }
    public static int size() { return properties.size(); }
    public static long generation() { return generation; }
    public static void clear() { properties = Map.of(); generation++; }

    /** Called on the server thread after recipe data has been loaded or reloaded. */
    public static void rebuild(MinecraftServer server, Logger logger) {
        Map<ResourceLocation, MaterialProperties> defaults = new HashMap<>();
        int inaccessibleMiningSpeeds = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            BlockState state = block.defaultBlockState();
            double destroySpeed;
            try {
                destroySpeed = state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            } catch (RuntimeException exception) {
                destroySpeed = 0;
                inaccessibleMiningSpeeds++;
            }
            int hardness = HardnessScale.fromMinecraft(destroySpeed, block.getExplosionResistance());
            defaults.put(id, new MaterialProperties(hardness, FALLBACK.ductilityPercent(), FALLBACK.elasticityPercent()));
        }

        Map<ResourceLocation, MaterialProperties> result = new HashMap<>(defaults);
        int inferred = inferFromRecipes(server, defaults, result);
        int overridden = applyOverrides(result, logger);
        properties = Map.copyOf(result);
        generation++;
        logger.info("[PDD-MATERIAL] rebuild generation={} blockTypes={} recipesInferred={} overrides={} miningFallback={} storage=BLOCK_ID_ATOMIC",
                generation, result.size(), inferred, overridden, inaccessibleMiningSpeeds);
    }

    private static int inferFromRecipes(MinecraftServer server,
                                        Map<ResourceLocation, MaterialProperties> defaults,
                                        Map<ResourceLocation, MaterialProperties> result) {
        List<RecipeHolder<?>> recipes = new ArrayList<>(server.getRecipeManager().getRecipes());
        recipes.sort(Comparator.comparing(holder -> holder.id().toString()));
        int inferred = 0;
        java.util.HashSet<ResourceLocation> claimed = new java.util.HashSet<>();
        for (RecipeHolder<?> holder : recipes) {
            try {
                ItemStack output = holder.value().getResultItem(server.registryAccess());
                if (!(output.getItem() instanceof BlockItem outputBlock)) continue;
                ResourceLocation outputId = BuiltInRegistries.BLOCK.getKey(outputBlock.getBlock());
                if (!defaults.containsKey(outputId) || claimed.contains(outputId)) continue;
                double hardness = 0, ductility = 0, elasticity = 0;
                int qualifyingSlots = 0;
                for (Ingredient ingredient : holder.value().getIngredients()) {
                    MaterialProperties options = averageOptions(ingredient, defaults);
                    if (options == null) continue;
                    hardness += options.hardness();
                    ductility += options.ductilityPercent();
                    elasticity += options.elasticityPercent();
                    qualifyingSlots++;
                }
                if (qualifyingSlots == 0) continue;
                MaterialProperties base = defaults.get(outputId);
                result.put(outputId, new MaterialProperties(
                        blend(base.hardness(), hardness / qualifyingSlots),
                        blend(base.ductilityPercent(), ductility / qualifyingSlots),
                        blend(base.elasticityPercent(), elasticity / qualifyingSlots)));
                claimed.add(outputId);
                inferred++;
            } catch (RuntimeException unsupportedRecipe) {
                // Unsupported custom recipes cannot prevent material registration.
            }
        }
        return inferred;
    }

    private static MaterialProperties averageOptions(Ingredient ingredient,
                                                     Map<ResourceLocation, MaterialProperties> defaults) {
        if (ingredient.isEmpty()) return null;
        double hardness = 0, ductility = 0, elasticity = 0;
        int count = 0;
        for (ItemStack stack : ingredient.getItems()) {
            if (!(stack.getItem() instanceof BlockItem item)) continue;
            MaterialProperties value = defaults.get(BuiltInRegistries.BLOCK.getKey(item.getBlock()));
            if (value == null) continue;
            hardness += value.hardness();
            ductility += value.ductilityPercent();
            elasticity += value.elasticityPercent();
            count++;
        }
        if (count == 0) return null;
        return new MaterialProperties((int) Math.round(hardness / count),
                (int) Math.round(ductility / count), (int) Math.round(elasticity / count));
    }

    private static int blend(double intrinsic, double ingredient) {
        return (int) Math.round(0.75 * intrinsic + 0.25 * ingredient);
    }

    private static int applyOverrides(Map<ResourceLocation, MaterialProperties> values, Logger logger) {
        Path path = FMLPaths.CONFIGDIR.get().resolve("pdd-materials.json");
        if (!Files.isRegularFile(path)) return 0;
        int applied = 0;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("blocks");
            if (root == null) return 0;
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                try {
                    ResourceLocation id = ResourceLocation.tryParse(entry.getKey());
                    if (id == null || !values.containsKey(id)) {
                        logger.warn("[PDD-MATERIAL] override ignored unregistered block={}", entry.getKey());
                        continue;
                    }
                    JsonObject override = entry.getValue().getAsJsonObject();
                    MaterialProperties old = values.get(id);
                    MaterialProperties replacement = new MaterialProperties(
                            getOrDefault(override, "hardness", old.hardness()),
                            getOrDefault(override, "ductilityPercent", old.ductilityPercent()),
                            getOrDefault(override, "elasticityPercent", old.elasticityPercent()));
                    values.put(id, replacement);
                    applied++;
                } catch (RuntimeException invalid) {
                    logger.warn("[PDD-MATERIAL] invalid override for {}: {}", entry.getKey(), invalid.getMessage());
                }
            }
        } catch (Exception exception) {
            logger.warn("[PDD-MATERIAL] override file unreadable; keeping inferred defaults path={}", path, exception);
        }
        return applied;
    }
    private static int getOrDefault(JsonObject object, String key, int fallback) {
        return object.has(key) ? object.get(key).getAsInt() : fallback;
    }
}
