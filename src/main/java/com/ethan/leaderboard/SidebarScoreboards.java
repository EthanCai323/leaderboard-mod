package com.ethan.leaderboard;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.Item;
import net.minecraft.network.packet.s2c.play.ScoreboardDisplayS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardObjectiveUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardScoreUpdateS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.stat.StatHandler;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.block.Block;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 个人侧边计分板：每个开启的玩家持有独立的 Scoreboard 实例，
 * 通过计分板数据包下发，各人只看得到自己的数据。
 * 数据源为在线玩家 StatHandler 实时值，每 20 tick 刷新一次。
 * 开启状态持久化到 leaderboard/scoreboard.json。
 */
public final class SidebarScoreboards {
    private static final String OBJECTIVE_NAME = "lb_sidebar";
    private static final String TITLE = "我的数据";

    /** 距离类 custom 统计（*_one_cm），与 compute() 的 distance 口径一致 */
    private static final List<String> DISTANCE_STATS = StatFormat.CUSTOM_STAT_NAMES.keySet().stream()
            .filter(k -> k.endsWith("_one_cm")).toList();

    private static final Set<String> enabled = new LinkedHashSet<>();
    private static final Map<UUID, PlayerBoard> boards = new HashMap<>();
    private static long tickCounter = 0;

    private record PlayerBoard(Scoreboard board, ScoreboardObjective objective) {
    }

    private SidebarScoreboards() {
    }

    // ---------- 持久化 ----------

    public static void load() {
        enabled.clear();
        Path path = path();
        if (!Files.exists(path)) {
            save();
            return;
        }
        try {
            JsonArray arr = JsonParser.parseString(Files.readString(path)).getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                enabled.add(arr.get(i).getAsString().toLowerCase(Locale.ROOT));
            }
        } catch (Exception e) {
            ServerLeaderboardMod.LOGGER.warn("[排行榜] 读取 scoreboard.json 失败: {}", e.toString());
        }
    }

    public static void save() {
        try {
            Files.createDirectories(path().getParent());
            JsonArray arr = new JsonArray();
            for (String name : enabled) {
                arr.add(name);
            }
            Files.writeString(path(), arr.toString());
        } catch (Exception e) {
            ServerLeaderboardMod.LOGGER.warn("[排行榜] 写入 scoreboard.json 失败: {}", e.toString());
        }
    }

    private static Path path() {
        return FabricLoader.getInstance().getGameDir().resolve("leaderboard").resolve("scoreboard.json");
    }

    // ---------- 开关 ----------

    public static boolean isEnabled(String name) {
        return enabled.contains(name.toLowerCase(Locale.ROOT));
    }

    public static void setEnabled(ServerPlayerEntity player, boolean on) {
        String key = player.getNameForScoreboard().toLowerCase(Locale.ROOT);
        if (on) {
            enabled.add(key);
            apply(player);
        } else {
            enabled.remove(key);
            remove(player);
        }
        save();
    }

    public static void onJoin(ServerPlayerEntity player) {
        if (isEnabled(player.getNameForScoreboard())) {
            apply(player);
        }
    }

    public static void onDisconnect(ServerPlayerEntity player) {
        boards.remove(player.getUuid());
    }

    // ---------- 计分板生命周期 ----------

    private static void apply(ServerPlayerEntity player) {
        Scoreboard board = new Scoreboard();
        ScoreboardObjective objective = board.addObjective(OBJECTIVE_NAME, ScoreboardCriterion.DUMMY,
                Text.literal(TITLE), ScoreboardCriterion.RenderType.INTEGER, false, null);
        board.setObjectiveSlot(ScoreboardDisplaySlot.SIDEBAR, objective);
        boards.put(player.getUuid(), new PlayerBoard(board, objective));
        player.networkHandler.sendPacket(
                new ScoreboardObjectiveUpdateS2CPacket(objective, ScoreboardObjectiveUpdateS2CPacket.ADD_MODE));
        player.networkHandler.sendPacket(
                new ScoreboardDisplayS2CPacket(ScoreboardDisplaySlot.SIDEBAR, objective));
        update(player);
    }

    private static void remove(ServerPlayerEntity player) {
        PlayerBoard pb = boards.remove(player.getUuid());
        if (pb != null) {
            player.networkHandler.sendPacket(new ScoreboardObjectiveUpdateS2CPacket(
                    pb.objective(), ScoreboardObjectiveUpdateS2CPacket.REMOVE_MODE));
        }
        // 恢复服务器主计分板的侧边栏显示（若主计分板本身有侧边栏目标）
        MinecraftServer server = player.getServer();
        if (server != null) {
            ScoreboardObjective main = server.getScoreboard().getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
            if (main != null) {
                player.networkHandler.sendPacket(
                        new ScoreboardDisplayS2CPacket(ScoreboardDisplaySlot.SIDEBAR, main));
            }
        }
    }

    // ---------- 定时刷新 ----------

    public static void tick(MinecraftServer server) {
        tickCounter++;
        if (tickCounter % 20 != 0 || boards.isEmpty()) {
            return;
        }
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (boards.containsKey(player.getUuid())) {
                update(player);
            }
        }
    }

    private static void update(ServerPlayerEntity player) {
        PlayerBoard pb = boards.get(player.getUuid());
        if (pb == null) {
            return;
        }
        StatHandler stats = player.getStatHandler();
        long playTime = customStat(stats, "minecraft:play_time");
        long deaths = customStat(stats, "minecraft:deaths");
        long mobKills = customStat(stats, "minecraft:mob_kills");
        long damageDealt = customStat(stats, "minecraft:damage_dealt");
        long damageTaken = customStat(stats, "minecraft:damage_taken");
        long aviate = customStat(stats, "minecraft:aviate_one_cm");
        long distance = 0;
        for (String id : DISTANCE_STATS) {
            distance += customStat(stats, id);
        }
        long mined = 0;
        for (Block block : Registries.BLOCK) {
            mined += stats.getStat(Stats.MINED, block);
        }
        long crafted = 0;
        for (Item item : Registries.ITEM) {
            crafted += stats.getStat(Stats.CRAFTED, item);
        }

        send(player, pb, "游戏时间(小时)", clampInt(playTime / 72000));
        send(player, pb, "死亡次数", clampInt(deaths));
        send(player, pb, "击杀生物", clampInt(mobKills));
        send(player, pb, "造成伤害(颗心)", clampInt(damageDealt / 10));
        send(player, pb, "承受伤害(颗心)", clampInt(damageTaken / 10));
        send(player, pb, "移动距离(米)", clampInt(distance / 100));
        send(player, pb, "挖掘", clampInt(mined));
        send(player, pb, "合成", clampInt(crafted));
        send(player, pb, "鞘翅飞行(米)", clampInt(aviate / 100));
    }

    private static long customStat(StatHandler stats, String id) {
        // StatType 内部用 IdentityHashMap 缓存统计项，必须传入注册表里的规范 Identifier 实例，
        // 直接传 Identifier.of(id) 新建实例会导致 hasStat 永远为 false、读到 0。
        Identifier key = Identifier.of(id);
        return Registries.CUSTOM_STAT.getOptionalValue(key)
                .map(canonical -> (long) stats.getStat(Stats.CUSTOM, canonical))
                .orElse(0L);
    }

    private static int clampInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static void send(ServerPlayerEntity player, PlayerBoard pb, String label, int value) {
        player.networkHandler.sendPacket(new ScoreboardScoreUpdateS2CPacket(
                label, pb.objective().getName(), value, Optional.empty(), Optional.empty()));
    }
}
