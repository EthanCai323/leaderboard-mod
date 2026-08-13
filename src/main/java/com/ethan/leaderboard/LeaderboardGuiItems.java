package com.ethan.leaderboard;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EntityType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.PotionItem;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.Optional;

/**
 * GUI 内物品图标的构建工具：命名、lore、统计项/分类 -> 图标映射。
 * 中文名与数值格式化统一复用 StatFormat。
 */
final class LeaderboardGuiItems {
    static final int COLOR_WHITE = 0xFFFFFF;
    static final int COLOR_GRAY = 0xAAAAAA;
    static final int COLOR_GREEN = 0x55FF55;
    static final int COLOR_GOLD = 0xFFAA00;
    static final int COLOR_RANK_1 = 0xFFD700;
    static final int COLOR_RANK_2 = 0xC0C0C0;
    static final int COLOR_RANK_3 = 0xB87333;

    private LeaderboardGuiItems() {
    }

    static Text styled(String text, int rgb) {
        return Text.literal(text).styled(s -> s.withItalic(false).withColor(rgb));
    }

    /** 名次颜色：金 / 银 / 铜 / 白 */
    static int rankColor(int rank) {
        return switch (rank) {
            case 1 -> COLOR_RANK_1;
            case 2 -> COLOR_RANK_2;
            case 3 -> COLOR_RANK_3;
            default -> COLOR_WHITE;
        };
    }

    /** 数据之王视图：custom 统计 -> 展示图标 */
    static Item iconForCustomStat(String stat) {
        if (stat.equals("minecraft:play_time") || stat.equals("minecraft:sneak_time")) {
            return Items.CLOCK;
        }
        if (stat.equals("minecraft:deaths")) {
            return Items.SKELETON_SKULL;
        }
        if (stat.equals("minecraft:mob_kills") || stat.equals("minecraft:player_kills")) {
            return Items.DIAMOND_SWORD;
        }
        if (stat.startsWith("minecraft:damage_")) {
            return (stat.equals("minecraft:damage_taken") || stat.equals("minecraft:damage_blocked_by_shield"))
                    ? Items.SHIELD : Items.IRON_SWORD;
        }
        if (stat.endsWith("_one_cm")) {
            return Items.LEATHER_BOOTS;
        }
        return switch (stat) {
            case "minecraft:jump" -> Items.RABBIT_FOOT;
            case "minecraft:fish_caught" -> Items.FISHING_ROD;
            case "minecraft:traded_with_villager", "minecraft:talked_to_villager" -> Items.EMERALD;
            case "minecraft:enchant_item" -> Items.ENCHANTING_TABLE;
            case "minecraft:sleep_in_bed" -> Items.RED_BED;
            case "minecraft:open_chest" -> Items.CHEST;
            default -> Items.BOOK;
        };
    }

    /** 物品分类 -> 展示图标 */
    static Item iconForCategory(String category) {
        return switch (category) {
            case "minecraft:mined" -> Items.GRASS_BLOCK;
            case "minecraft:crafted" -> Items.CRAFTING_TABLE;
            case "minecraft:used" -> Items.FLINT_AND_STEEL;
            case "minecraft:broken" -> Items.ANVIL;
            case "minecraft:picked_up" -> Items.HOPPER;
            case "minecraft:dropped" -> Items.DROPPER;
            case "minecraft:killed" -> Items.DIAMOND_SWORD;
            case "minecraft:killed_by" -> Items.SKELETON_SKULL;
            default -> Items.CHEST;
        };
    }

    /**
     * 物品分类明细：直接用统计项对应的物品/方块本身做图标。
     * 实体类统计（killed/killed_by）等拿不到物品时用分类图标兜底。
     */
    static Item resolveStatItem(String stat, String category) {
        Optional<Item> item = Registries.ITEM.getOptionalValue(Identifier.of(stat));
        return item.filter(i -> i != Items.AIR).orElse(iconForCategory(category));
    }

    /** 生物明细：尽量用对应生物的刷怪蛋，取不到用骷髅头兜底 */
    static Item spawnEggFor(String entityStatId) {
        Optional<EntityType<?>> type = Registries.ENTITY_TYPE.getOptionalValue(Identifier.of(entityStatId));
        if (type.isPresent()) {
            SpawnEggItem egg = SpawnEggItem.forEntity(type.get());
            if (egg != null) {
                return egg;
            }
        }
        return Items.SKELETON_SKULL;
    }

    /**
     * 食物与饮品判定：物品组件含 FOOD，或为药水类（普通/喷溅/滞留），
     * 或为不祥之瓶、牛奶桶。
     */
    static boolean isFoodOrDrink(Item item) {
        if (item == Items.MILK_BUCKET || item == Items.OMINOUS_BOTTLE) {
            return true;
        }
        if (item instanceof PotionItem) {
            return true;
        }
        return item.getDefaultStack().getComponents().contains(DataComponentTypes.FOOD);
    }
}
