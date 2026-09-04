package com.ethan.leaderboard;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.packet.s2c.play.ScoreboardDisplayS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardObjectiveUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardScoreUpdateS2CPacket;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 个人侧边计分板：每个开启的玩家持有独立的 Scoreboard 实例，
 * 通过计分板数据包下发，各人只看得到自己的数据。
 * 数据源为最近一次排行榜生成时从 stats JSON 求和得到的玩家总览，
 * 按 LeaderboardGenerator 的数据版本号驱动：版本变化才重建并重发，
 * 玩家刚开启时立即显示一次当前数据。
 * 开启状态持久化到 leaderboard/scoreboard.json。
 * OP 可通过 /leaderboard allowscoreboard false 禁止普通玩家开启。
 */
public final class SidebarScoreboards {
    private static final String OBJECTIVE_NAME = "lb_sidebar";
    private static final String TITLE = "我的数据";

    private static final Set<String> enabled = new LinkedHashSet<>();
    private static final Map<UUID, PlayerBoard> boards = new HashMap<>();
    /** 最近一次向玩家推送的数据版本号，-1 表示从未推送 */
    private static long lastPushedVersion = -1L;

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

    /** 玩家是否允许开启侧边计分板（OP 不受 allowScoreboard 限制） */
    public static boolean mayUse(ServerPlayerEntity player) {
        return LeaderboardConfig.get().allowScoreboard || player.hasPermissionLevel(2);
    }

    /**
     * 开关个人侧边计分板。
     * 返回 false 表示被 allowScoreboard=false 拦截（未做任何变更）。
     */
    public static boolean setEnabled(ServerPlayerEntity player, boolean on) {
        if (on && !mayUse(player)) {
            return false;
        }
        String key = player.getNameForScoreboard().toLowerCase(Locale.ROOT);
        if (on) {
            enabled.add(key);
            apply(player);
        } else {
            enabled.remove(key);
            remove(player);
        }
        save();
        return true;
    }

    /**
     * allowScoreboard 被切为 false 时调用：关闭所有非 OP 玩家已开启的计分板。
     */
    public static void disableAllForNonOps(MinecraftServer server) {
        boolean changed = false;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player.hasPermissionLevel(2)) {
                continue;
            }
            String key = player.getNameForScoreboard().toLowerCase(Locale.ROOT);
            if (enabled.remove(key)) {
                changed = true;
            }
            remove(player);
            player.sendMessage(Text.literal("管理员已关闭个人侧边计分板")
                    .formatted(net.minecraft.util.Formatting.RED), false);
        }
        if (changed) {
            save();
        }
    }

    public static void onJoin(ServerPlayerEntity player) {
        if (isEnabled(player.getNameForScoreboard()) && mayUse(player)) {
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

    // ---------- 按需刷新 ----------

    /** 每 tick 调用：数据版本号变化时才重建并重发所有已开启的计分板 */
    public static void tick(MinecraftServer server) {
        if (boards.isEmpty()) {
            return;
        }
        long version = LeaderboardGenerator.getDataVersion();
        if (version == lastPushedVersion) {
            return;
        }
        lastPushedVersion = version;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (boards.containsKey(player.getUuid())) {
                update(player);
            }
        }
    }

    /** 从最近一次排行榜生成的玩家总览取数，不再每 tick 遍历注册表 */
    private static void update(ServerPlayerEntity player) {
        PlayerBoard pb = boards.get(player.getUuid());
        if (pb == null) {
            return;
        }
        LeaderboardData data = LeaderboardGenerator.getLastData();
        LeaderboardData.PlayerSummary ps = data == null
                ? null
                : data.getPlayerSummary().get(player.getGameProfile().getName());
        long playTime = ps == null ? 0 : ps.playTime();
        long deaths = ps == null ? 0 : ps.deaths();
        long mobKills = ps == null ? 0 : ps.mobKills();
        long damageDealt = ps == null ? 0 : ps.damageDealt();
        long damageTaken = ps == null ? 0 : ps.damageTaken();
        long distance = ps == null ? 0 : ps.distance();
        long mined = ps == null ? 0 : ps.minedTotal();
        long crafted = ps == null ? 0 : ps.craftedTotal();
        long aviate = ps == null ? 0 : ps.aviate();

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

    private static int clampInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static void send(ServerPlayerEntity player, PlayerBoard pb, String label, int value) {
        player.networkHandler.sendPacket(new ScoreboardScoreUpdateS2CPacket(
                label, pb.objective().getName(), value, Optional.empty(), Optional.empty()));
    }
}
