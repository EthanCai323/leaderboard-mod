package com.ethan.leaderboard;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 统计项中文名表 / 单位换算，移植自 leaderboard.py
 */
public final class StatFormat {
    public static final String BOT_PREFIX = "bot_";

    /** 不计入排行的“计时器”类统计 */
    public static final Set<String> EXCLUDED_STATS = Set.of(
            "minecraft:time_since_death",
            "minecraft:time_since_rest",
            "minecraft:total_world_time");

    private static final Set<String> TICK_STATS = Set.of(
            "minecraft:play_time", "minecraft:sneak_time");

    private static final String CM_SUFFIX = "_one_cm";

    private static final Set<String> DAMAGE_STATS = Set.of(
            "minecraft:damage_dealt", "minecraft:damage_taken",
            "minecraft:damage_blocked_by_shield", "minecraft:damage_absorbed",
            "minecraft:damage_resisted", "minecraft:damage_dealt_absorbed",
            "minecraft:damage_dealt_resisted");

    public static final Map<String, String> CUSTOM_STAT_NAMES = new LinkedHashMap<>();

    static {
        Map<String, String> m = CUSTOM_STAT_NAMES;
        m.put("minecraft:play_time", "游戏时间");
        m.put("minecraft:deaths", "死亡次数");
        m.put("minecraft:mob_kills", "击杀生物");
        m.put("minecraft:player_kills", "击杀玩家");
        m.put("minecraft:damage_dealt", "造成伤害");
        m.put("minecraft:damage_taken", "承受伤害");
        m.put("minecraft:damage_blocked_by_shield", "盾牌格挡伤害");
        m.put("minecraft:damage_absorbed", "吸收伤害");
        m.put("minecraft:damage_resisted", "抵抗伤害");
        m.put("minecraft:jump", "跳跃次数");
        m.put("minecraft:drop", "丢弃物品");
        m.put("minecraft:walk_one_cm", "步行距离");
        m.put("minecraft:sprint_one_cm", "疾跑距离");
        m.put("minecraft:fly_one_cm", "飞行距离");
        m.put("minecraft:fall_one_cm", "坠落距离");
        m.put("minecraft:swim_one_cm", "游泳距离");
        m.put("minecraft:climb_one_cm", "攀爬距离");
        m.put("minecraft:crouch_one_cm", "潜行移动距离");
        m.put("minecraft:aviate_one_cm", "鞘翅飞行");
        m.put("minecraft:boat_one_cm", "划船距离");
        m.put("minecraft:horse_one_cm", "骑马距离");
        m.put("minecraft:minecart_one_cm", "矿车距离");
        m.put("minecraft:pig_one_cm", "骑猪距离");
        m.put("minecraft:strider_one_cm", "骑炽足兽距离");
        m.put("minecraft:happy_ghast_one_cm", "骑乘快乐恶魂距离");
        m.put("minecraft:walk_on_water_one_cm", "水面行走距离");
        m.put("minecraft:walk_under_water_one_cm", "水下行走距离");
        m.put("minecraft:sneak_time", "潜行时间");
        m.put("minecraft:fish_caught", "钓鱼数量");
        m.put("minecraft:animals_bred", "繁殖动物");
        m.put("minecraft:talked_to_villager", "与村民交谈");
        m.put("minecraft:traded_with_villager", "与村民交易");
        m.put("minecraft:eat_cake_slice", "吃掉蛋糕片");
        m.put("minecraft:enchant_item", "附魔物品");
        m.put("minecraft:play_record", "播放唱片");
        m.put("minecraft:sleep_in_bed", "睡觉次数");
        m.put("minecraft:raid_win", "袭击胜利");
        m.put("minecraft:raid_trigger", "触发袭击");
        m.put("minecraft:bell_ring", "敲钟次数");
        m.put("minecraft:open_chest", "打开箱子");
        m.put("minecraft:open_enderchest", "打开末影箱");
        m.put("minecraft:open_barrel", "打开木桶");
        m.put("minecraft:open_shulker_box", "打开潜影盒");
        m.put("minecraft:interact_with_furnace", "使用熔炉");
        m.put("minecraft:interact_with_blast_furnace", "使用高炉");
        m.put("minecraft:interact_with_smoker", "使用烟熏炉");
        m.put("minecraft:interact_with_crafting_table", "使用工作台");
        m.put("minecraft:interact_with_beacon", "使用信标");
        m.put("minecraft:interact_with_brewingstand", "使用酿造台");
        m.put("minecraft:interact_with_anvil", "使用铁砧");
        m.put("minecraft:interact_with_grindstone", "使用砂轮");
        m.put("minecraft:interact_with_smithing_table", "使用锻造台");
        m.put("minecraft:interact_with_stonecutter", "使用切石机");
        m.put("minecraft:interact_with_cartography_table", "使用制图台");
        m.put("minecraft:interact_with_loom", "使用织布机");
        m.put("minecraft:interact_with_lectern", "使用讲台");
        m.put("minecraft:interact_with_campfire", "使用营火");
        m.put("minecraft:interact_with_hopper", "使用漏斗");
        m.put("minecraft:inspect_hopper", "查看漏斗");
        m.put("minecraft:inspect_dropper", "查看投掷器");
        m.put("minecraft:inspect_dispenser", "查看发射器");
        m.put("minecraft:trigger_trapped_chest", "触发陷阱箱");
        m.put("minecraft:target_hit", "命中靶心");
        m.put("minecraft:tune_noteblock", "调音符盒");
        m.put("minecraft:play_noteblock", "演奏音符盒");
        m.put("minecraft:pot_flower", "盆栽花卉");
        m.put("minecraft:fill_cauldron", "装满炼药锅");
        m.put("minecraft:use_cauldron", "使用炼药锅");
        m.put("minecraft:clean_armor", "清洗护甲");
        m.put("minecraft:clean_banner", "清洗旗帜");
        m.put("minecraft:clean_shulker_box", "清洗潜影盒");
        m.put("minecraft:leave_game", "离开游戏次数");
    }

    /** 8 个物品分类（保持与 Python 一致的输出顺序） */
    public static final Map<String, String> CATEGORY_NAMES = new LinkedHashMap<>();

    static {
        CATEGORY_NAMES.put("minecraft:mined", "挖掘");
        CATEGORY_NAMES.put("minecraft:crafted", "合成");
        CATEGORY_NAMES.put("minecraft:used", "使用");
        CATEGORY_NAMES.put("minecraft:broken", "损坏");
        CATEGORY_NAMES.put("minecraft:picked_up", "拾取");
        CATEGORY_NAMES.put("minecraft:dropped", "丢弃");
        CATEGORY_NAMES.put("minecraft:killed", "击杀生物");
        CATEGORY_NAMES.put("minecraft:killed_by", "死于生物");
    }

    /** 每类物品统计最多展示的条目数（按全体玩家总量排序） */
    public static final int TOP_ITEMS_PER_CATEGORY = 30;

    private StatFormat() {
    }

    /** minecraft:diamond_ore -> Diamond Ore */
    public static String prettifyId(String fullId) {
        String raw = fullId.contains(":") ? fullId.substring(fullId.indexOf(':') + 1) : fullId;
        String[] parts = raw.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                sb.append(part.substring(1));
            }
        }
        return sb.toString();
    }

    public static String statDisplayName(String statId) {
        return CUSTOM_STAT_NAMES.getOrDefault(statId, prettifyId(statId));
    }

    /** 按统计类型格式化数值（与 Python format_value 一致） */
    public static String formatValue(String statId, long value) {
        if (TICK_STATS.contains(statId)) {
            double hours = value / 20.0 / 3600.0;
            if (hours >= 1) {
                return String.format(Locale.US, "%.1f 小时", hours);
            }
            double minutes = value / 20.0 / 60.0;
            return String.format(Locale.US, "%.0f 分钟", minutes);
        }
        if (statId.endsWith(CM_SUFFIX)) {
            double km = value / 100.0 / 1000.0;
            if (km >= 1) {
                return String.format(Locale.US, "%.2f km", km);
            }
            return String.format(Locale.US, "%.0f m", value / 100.0);
        }
        if (DAMAGE_STATS.contains(statId)) {
            return String.format(Locale.US, "%.1f 颗心", value / 10.0);
        }
        return String.format(Locale.US, "%,d", value);
    }

    /** 千分位数字 */
    public static String num(long value) {
        return String.format(Locale.US, "%,d", value);
    }

    /** HTML 转义 */
    public static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
