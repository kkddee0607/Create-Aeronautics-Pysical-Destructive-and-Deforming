package com.physicaldestroydeform.material;

import com.physicaldestroydeform.MaterialProperties;

/** Explicit *vanilla* calibration anchors and provisional material/functional profiles.
 * Unknown modded IDs are never classified by name substring. Recipe/registry fallback is used. */
public final class MaterialProfiles {
    private MaterialProfiles() {}
    public static boolean explicit(String id) {
        return switch(id) {
            case "minecraft:reinforced_deepslate","minecraft:obsidian","minecraft:slime_block",
                    "minecraft:gold_block","minecraft:iron_block","minecraft:copper_block",
                    "minecraft:oak_planks","minecraft:stone","minecraft:glass","minecraft:dirt" -> true;
            default -> false;
        };
    }
    /** null means that an unrecognized block ID has no explicit material profile. */
    public static MaterialProperties profile(String id, MaterialProperties base) {
        if(!explicit(id))return null;
        if(base==null)throw new IllegalArgumentException("null material candidate");
        int h=base.hardness(),t=base.threshold(),d=base.ductilityPercent(),e=base.elasticityPercent();
        switch(id) {
            case "minecraft:reinforced_deepslate" -> {h=255;t=255;d=10;e=20;}
            case "minecraft:obsidian" -> {d=0;e=0;}
            case "minecraft:slime_block" -> {d=80;e=100;}
            case "minecraft:gold_block" -> {d=100;e=20;}
            case "minecraft:iron_block" -> {d=70;e=35;}
            case "minecraft:copper_block" -> {d=85;e=25;}
            case "minecraft:oak_planks" -> {d=35;e=30;}
            case "minecraft:stone" -> {d=10;e=15;}
            case "minecraft:glass" -> {t=Math.min(35,t);d=3;e=5;}
            case "minecraft:dirt" -> {d=25;e=10;}
            default -> throw new IllegalStateException("unreachable profile");
        }
        return new MaterialProperties(h,t,d,e,base.unbreakable());
    }
}
