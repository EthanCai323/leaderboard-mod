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

    /** 通用榜：custom 统计 -> 展示图标（逐条对应语义，避免书与鞋子兜底） */
    static Item iconForCustomStat(String stat) {
        return switch (stat) {
            // 时间类
            case "minecraft:play_time" -> Items.CLOCK;
            case "minecraft:sneak_time" -> Items.SCULK_SENSOR;
            // 战斗类
            case "minecraft:deaths" -> Items.SKELETON_SKULL;
            case "minecraft:mob_kills" -> Items.DIAMOND_SWORD;
            case "minecraft:player_kills" -> Items.IRON_AXE;
            case "minecraft:damage_dealt" -> Items.IRON_SWORD;
            case "minecraft:damage_taken", "minecraft:damage_blocked_by_shield" -> Items.SHIELD;
            case "minecraft:damage_absorbed" -> Items.GOLDEN_APPLE;
            case "minecraft:damage_resisted" -> Items.NETHERITE_CHESTPLATE;
            // 移动类
            case "minecraft:walk_one_cm" -> Items.LEATHER_BOOTS;
            case "minecraft:sprint_one_cm" -> Items.RABBIT_FOOT;
            case "minecraft:fly_one_cm" -> Items.FEATHER;
            case "minecraft:fall_one_cm" -> Items.SLIME_BLOCK;
            case "minecraft:swim_one_cm" -> Items.TURTLE_HELMET;
            case "minecraft:climb_one_cm" -> Items.LADDER;
            case "minecraft:crouch_one_cm" -> Items.SCULK_CATALYST;
            case "minecraft:aviate_one_cm" -> Items.ELYTRA;
            case "minecraft:boat_one_cm" -> Items.OAK_BOAT;
            case "minecraft:horse_one_cm" -> Items.SADDLE;
            case "minecraft:minecart_one_cm" -> Items.MINECART;
            case "minecraft:pig_one_cm" -> Items.CARROT_ON_A_STICK;
            case "minecraft:strider_one_cm" -> Items.WARPED_FUNGUS_ON_A_STICK;
            case "minecraft:happy_ghast_one_cm" -> Items.GHAST_TEAR;
            case "minecraft:walk_on_water_one_cm" -> Items.ICE;
            case "minecraft:walk_under_water_one_cm" -> Items.WATER_BUCKET;
            case "minecraft:jump" -> Items.SLIME_BALL;
            // 互动类
            case "minecraft:fish_caught" -> Items.FISHING_ROD;
            case "minecraft:animals_bred" -> Items.WHEAT;
            case "minecraft:traded_with_villager" -> Items.EMERALD;
            case "minecraft:talked_to_villager" -> Items.PAPER;
            case "minecraft:eat_cake_slice" -> Items.CAKE;
            case "minecraft:enchant_item" -> Items.ENCHANTING_TABLE;
            case "minecraft:play_record" -> Items.JUKEBOX;
            case "minecraft:sleep_in_bed" -> Items.RED_BED;
            case "minecraft:raid_win" -> Items.TOTEM_OF_UNDYING;
            case "minecraft:raid_trigger" -> Items.OMINOUS_BOTTLE;
            case "minecraft:bell_ring" -> Items.BELL;
            case "minecraft:drop" -> Items.DROPPER;
            case "minecraft:leave_game" -> Items.OAK_DOOR;
            // 容器类
            case "minecraft:open_chest" -> Items.CHEST;
            case "minecraft:open_enderchest" -> Items.ENDER_CHEST;
            case "minecraft:open_barrel" -> Items.BARREL;
            case "minecraft:open_shulker_box" -> Items.SHULKER_BOX;
            case "minecraft:trigger_trapped_chest" -> Items.TRAPPED_CHEST;
            case "minecraft:inspect_hopper", "minecraft:interact_with_hopper" -> Items.HOPPER;
            case "minecraft:inspect_dropper" -> Items.DROPPER;
            case "minecraft:inspect_dispenser" -> Items.DISPENSER;
            // 方块交互类
            case "minecraft:interact_with_furnace" -> Items.FURNACE;
            case "minecraft:interact_with_blast_furnace" -> Items.BLAST_FURNACE;
            case "minecraft:interact_with_smoker" -> Items.SMOKER;
            case "minecraft:interact_with_crafting_table" -> Items.CRAFTING_TABLE;
            case "minecraft:interact_with_beacon" -> Items.BEACON;
            case "minecraft:interact_with_brewingstand" -> Items.BREWING_STAND;
            case "minecraft:interact_with_anvil" -> Items.ANVIL;
            case "minecraft:interact_with_grindstone" -> Items.GRINDSTONE;
            case "minecraft:interact_with_smithing_table" -> Items.SMITHING_TABLE;
            case "minecraft:interact_with_stonecutter" -> Items.STONECUTTER;
            case "minecraft:interact_with_cartography_table" -> Items.CARTOGRAPHY_TABLE;
            case "minecraft:interact_with_loom" -> Items.LOOM;
            case "minecraft:interact_with_lectern" -> Items.LECTERN;
            case "minecraft:interact_with_campfire" -> Items.CAMPFIRE;
            case "minecraft:target_hit" -> Items.TARGET;
            case "minecraft:tune_noteblock", "minecraft:play_noteblock" -> Items.NOTE_BLOCK;
            case "minecraft:pot_flower" -> Items.FLOWER_POT;
            case "minecraft:fill_cauldron", "minecraft:use_cauldron" -> Items.CAULDRON;
            case "minecraft:clean_armor" -> Items.LEATHER_CHESTPLATE;
            case "minecraft:clean_banner" -> Items.WHITE_BANNER;
            case "minecraft:clean_shulker_box" -> Items.SHULKER_SHELL;
            default -> defaultCustomIcon(stat);
        };
    }

    /** 兜底：其余距离类用靴子，其余伤害类用剑，最后才是书 */
    private static Item defaultCustomIcon(String stat) {
        if (stat.startsWith("minecraft:damage_")) {
            return Items.IRON_SWORD;
        }
        if (stat.endsWith("_one_cm")) {
            return Items.LEATHER_BOOTS;
        }
        return Items.BOOK;
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
     * 食物与饮品判定：物品组件含 FOOD，或为普通药水/不祥之瓶/牛奶桶。
     * 鱼桶与喷溅、滞留型药水归普通物品分类。
     */
    static boolean isFoodOrDrink(Item item) {
        if (item == Items.SPLASH_POTION || item == Items.LINGERING_POTION) {
            return false;
        }
        if (isFishOrMobBucket(item)) {
            return false;
        }
        if (item == Items.MILK_BUCKET || item == Items.OMINOUS_BOTTLE) {
            return true;
        }
        if (item instanceof PotionItem) {
            return true;
        }
        return item.getDefaultStack().getComponents().contains(DataComponentTypes.FOOD);
    }

    /** 各种鱼桶与生物桶（鳕鱼/鲑鱼/热带鱼/河豚/美西螈/蝌蚪） */
    private static boolean isFishOrMobBucket(Item item) {
        return item == Items.COD_BUCKET || item == Items.SALMON_BUCKET
                || item == Items.TROPICAL_FISH_BUCKET || item == Items.PUFFERFISH_BUCKET
                || item == Items.AXOLOTL_BUCKET || item == Items.TADPOLE_BUCKET;
    }
}
