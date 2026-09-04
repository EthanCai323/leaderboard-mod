package com.ethan.leaderboard;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
import net.minecraft.util.WorldSavePath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 个人侧边计分板：每个开启的玩家持有独立的 Scoreboard 实例，
 * 通过计分板数据包下发，各人只看得到自己的数据。
 * 两种刷新方式共存：
 * 1. 数据版本号驱动：排行榜重新生成后重建并重发（默认）；
 * 2. 间隔驱动：scoreboardRefreshIntervalTicks 大于 0 时按间隔从玩家 stats JSON
 *    实时读取 9 项数据下发，读文件在后台线程，主线程只发包。
 * 玩家刚开启时立即显示一次当前数据。
 * 开启状态持久化到 leaderboard/scoreboard.json，手动编辑后 5 秒内自动生效。
 * OP 可通过 /leaderboard allowscoreboard false 禁止普通玩家开启。
 */
public final class SidebarScoreboards {
    private static final String OBJECTIVE_NAME = "lb_sidebar";
    private static final String TITLE = "我的数据";
    /** scoreboard.json 外部变更检测周期（毫秒），5 秒 */
    private static final long WATCH_INTERVAL_MS = 5000L;

    /** watcher 线程与主线程都会访问，需线程安全 */
    private static final Set<String> enabled = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final Map<UUID, PlayerBoard> boards = new HashMap<>();
    /** 最近一次向玩家推送的数据版本号，-1 表示从未推送 */
    private static long lastPushedVersion = -1L;
    /** scoreboard.json 已知的最后修改时间，-1 表示文件不存在，Long.MIN_VALUE 表示未初始化 */
    private static volatile long fileModified = Long.MIN_VALUE;
    private static long tickCounter = 0;

    /** 间隔驱动刷新：后台读取 stats JSON 的单线程执行器 */
    private static final ExecutorService READER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "server-leaderboard-scoreboard-reader");
        t.setDaemon(true);
        return t;
    });
    /** 上一次 stats 读取未完成时跳过本次，避免重复读 */
    private static final AtomicBoolean READING = new AtomicBoolean(false);

    /**
     * scoreboard.json 外部编辑监视线程。必须独立于服务器 tick：
     * 1.21 起服务器空置 60 秒会自动暂停（pause-when-empty），暂停期间 tick 停止，
     * 挂在 tick 上的检测会完全失效。
     */
    private static ScheduledExecutorService watcher;

    private record PlayerBoard(Scoreboard board, ScoreboardObjective objective) {
    }

    /** 计分板展示的 9 项核心数据，口径与 LeaderboardData.PlayerSummary 一致 */
    private record NineStats(long playTime, long deaths, long mobKills, long damageDealt,
                             long damageTaken, long distance, long mined, long crafted, long aviate) {
        static final NineStats ZERO = new NineStats(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private SidebarScoreboards() {
    }

    // ---------- 持久化 ----------

    public static void load() {
        Path path = path();
        if (!Files.exists(path)) {
            fileModified = -1L;
            synchronized (enabled) {
                enabled.clear();
            }
            save();
            return;
        }
        try {
            JsonArray arr = JsonParser.parseString(Files.readString(path)).getAsJsonArray();
            synchronized (enabled) {
                enabled.clear();
                for (int i = 0; i < arr.size(); i++) {
                    enabled.add(arr.get(i).getAsString().toLowerCase(Locale.ROOT));
                }
            }
            fileModified = Files.getLastModifiedTime(path).toMillis();
        } catch (Exception e) {
            ServerLeaderboardMod.LOGGER.warn("[排行榜] 读取 scoreboard.json 失败: {}", e.toString());
        }
    }

    public static void save() {
        try {
            Files.createDirectories(path().getParent());
            JsonArray arr = new JsonArray();
            synchronized (enabled) {
                for (String name : enabled) {
                    arr.add(name);
                }
            }
            Files.writeString(path(), arr.toString());
            // 自己写入导致的 mtime 变化不应触发外部变更重载
            fileModified = Files.getLastModifiedTime(path()).toMillis();
        } catch (Exception e) {
            ServerLeaderboardMod.LOGGER.warn("[排行榜] 写入 scoreboard.json 失败: {}", e.toString());
        }
    }

    /** 名单变更后与在线玩家对齐：新加入名单的立即显示计分板，被移出的立即移除 */
    public static void reconcile(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            boolean shouldHave = isEnabled(player.getNameForScoreboard()) && mayUse(player);
            boolean has = boards.containsKey(player.getUuid());
            if (shouldHave && !has) {
                apply(player);
            } else if (!shouldHave && has) {
                remove(player);
            }
        }
    }

    /**
     * 服务器启动完成后开启 scoreboard.json 监视线程（每 5 秒检测一次 mtime）。
     * 独立线程不随服务器暂停而停摆，空服时的外部编辑也能检出。
     */
    public static void startWatcher(MinecraftServer server) {
        watcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "server-leaderboard-scoreboard-watcher");
            t.setDaemon(true);
            return t;
        });
        watcher.scheduleWithFixedDelay(() -> checkExternalEdit(server),
                WATCH_INTERVAL_MS, WATCH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** 服务器停止时关闭后台线程 */
    public static void shutdown() {
        if (watcher != null) {
            watcher.shutdown();
            try {
                if (!watcher.awaitTermination(5, TimeUnit.SECONDS)) {
                    watcher.shutdownNow();
                }
            } catch (InterruptedException e) {
                watcher.shutdownNow();
                Thread.currentThread().interrupt();
            }
            watcher = null;
        }
        READER.shutdown();
        try {
            if (!READER.awaitTermination(5, TimeUnit.SECONDS)) {
                READER.shutdownNow();
            }
        } catch (InterruptedException e) {
            READER.shutdownNow();
            Thread.currentThread().interrupt();
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

    // ---------- 刷新 ----------

    /**
     * 每 tick 调用：
     * 1. 排行榜数据版本号变化时重发所有已开启的计分板；
     * 2. scoreboardRefreshIntervalTicks 大于 0 时按间隔触发实时刷新。
     * 注意：scoreboard.json 外部编辑检测不在这里，由独立 watcher 线程负责
     * （服务器空置暂停时 tick 停发，挂在这里会检测不到）。
     */
    public static void tick(MinecraftServer server) {
        tickCounter++;
        if (boards.isEmpty()) {
            return;
        }
        long version = LeaderboardGenerator.getDataVersion();
        if (version != lastPushedVersion) {
            lastPushedVersion = version;
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (boards.containsKey(player.getUuid())) {
                    update(player);
                }
            }
        }
        long interval = LeaderboardConfig.get().scoreboardRefreshIntervalTicks;
        if (interval > 0 && tickCounter % interval == 0) {
            triggerLiveRefresh(server);
        }
    }

    /**
     * watcher 线程每 5 秒调用：scoreboard.json 的 mtime 变化就重新加载，
     * 立即打日志，再把计分板对齐任务投回主线程执行
     * （暂停时主线程任务排队，玩家上线唤醒后执行；无人在线时 reconcile 本就是空操作）。
     */
    private static void checkExternalEdit(MinecraftServer server) {
        try {
            Path path = path();
            long current = Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : -1L;
            if (fileModified == Long.MIN_VALUE) {
                // 首次仅建立基线，启动时的 load() 已读取过内容
                fileModified = current;
                return;
            }
            if (current != fileModified) {
                load();
                ServerLeaderboardMod.LOGGER.info("[排行榜] 检测到 scoreboard.json 变更，已重新加载");
                server.execute(() -> reconcile(server));
            }
        } catch (Exception ignored) {
            // 读取 mtime 失败时跳过本次检测
        }
    }

    /**
     * 间隔驱动的实时刷新：后台线程从各玩家 stats JSON 读取 9 项数据，
     * 主线程只负责发包；上一次读取未完成时跳过本次，避免重复读。
     */
    private static void triggerLiveRefresh(MinecraftServer server) {
        if (!READING.compareAndSet(false, true)) {
            return;
        }
        Path statsDir = server.getSavePath(WorldSavePath.ROOT).resolve("stats");
        Map<UUID, ServerPlayerEntity> targets = new HashMap<>();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (boards.containsKey(player.getUuid())) {
                targets.put(player.getUuid(), player);
            }
        }
        if (targets.isEmpty()) {
            READING.set(false);
            return;
        }
        READER.submit(() -> {
            Map<UUID, NineStats> result = new HashMap<>();
            try {
                for (UUID uuid : targets.keySet()) {
                    result.put(uuid, readNineStats(statsDir.resolve(uuid + ".json")));
                }
            } catch (Exception e) {
                ServerLeaderboardMod.LOGGER.warn("[排行榜] 计分板实时刷新读取失败: {}", e.toString());
            }
            server.execute(() -> {
                try {
                    for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                        NineStats stats = result.get(player.getUuid());
                        PlayerBoard pb = boards.get(player.getUuid());
                        if (stats != null && pb != null) {
                            push(player, pb, stats);
                        }
                    }
                } finally {
                    READING.set(false);
                }
            });
        });
    }

    /** 后台线程：从单个玩家的 stats JSON 计算 9 项核心数据，文件缺失或损坏时返回全零 */
    private static NineStats readNineStats(Path path) {
        try {
            if (!Files.exists(path)) {
                return NineStats.ZERO;
            }
            JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            JsonObject stats = root.has("stats") && root.get("stats").isJsonObject()
                    ? root.getAsJsonObject("stats") : new JsonObject();
            Map<String, Map<String, Long>> parsed = LeaderboardGenerator.parseStats(stats);
            Map<String, Long> custom = parsed.getOrDefault("minecraft:custom", Map.of());
            long distance = 0;
            for (Map.Entry<String, Long> e : custom.entrySet()) {
                if (e.getKey().endsWith("_one_cm")) {
                    distance += e.getValue();
                }
            }
            return new NineStats(
                    custom.getOrDefault("minecraft:play_time", 0L),
                    custom.getOrDefault("minecraft:deaths", 0L),
                    custom.getOrDefault("minecraft:mob_kills", 0L),
                    custom.getOrDefault("minecraft:damage_dealt", 0L),
                    custom.getOrDefault("minecraft:damage_taken", 0L),
                    distance,
                    sumCategory(parsed, "minecraft:mined"),
                    sumCategory(parsed, "minecraft:crafted"),
                    custom.getOrDefault("minecraft:aviate_one_cm", 0L));
        } catch (Exception e) {
            return NineStats.ZERO;
        }
    }

    /** 物品分类求和，与玩家总览的挖掘/合成口径一致 */
    private static long sumCategory(Map<String, Map<String, Long>> parsed, String category) {
        Map<String, Long> m = parsed.get(category);
        if (m == null) {
            return 0;
        }
        long total = 0;
        for (long v : m.values()) {
            total += v;
        }
        return total;
    }

    /** 从最近一次排行榜生成的玩家总览取数，版本号驱动路径使用 */
    private static void update(ServerPlayerEntity player) {
        PlayerBoard pb = boards.get(player.getUuid());
        if (pb == null) {
            return;
        }
        LeaderboardData data = LeaderboardGenerator.getLastData();
        LeaderboardData.PlayerSummary ps = data == null
                ? null
                : data.getPlayerSummary().get(player.getGameProfile().getName());
        NineStats stats = ps == null ? NineStats.ZERO : new NineStats(
                ps.playTime(), ps.deaths(), ps.mobKills(), ps.damageDealt(), ps.damageTaken(),
                ps.distance(), ps.minedTotal(), ps.craftedTotal(), ps.aviate());
        push(player, pb, stats);
    }

    /** 下发 9 项数据到玩家计分板 */
    private static void push(ServerPlayerEntity player, PlayerBoard pb, NineStats s) {
        send(player, pb, "游戏时间(小时)", clampInt(s.playTime() / 72000));
        send(player, pb, "死亡次数", clampInt(s.deaths()));
        send(player, pb, "击杀生物", clampInt(s.mobKills()));
        send(player, pb, "造成伤害(颗心)", clampInt(s.damageDealt() / 10));
        send(player, pb, "承受伤害(颗心)", clampInt(s.damageTaken() / 10));
        send(player, pb, "移动距离(米)", clampInt(s.distance() / 100));
        send(player, pb, "挖掘", clampInt(s.mined()));
        send(player, pb, "合成", clampInt(s.crafted()));
        send(player, pb, "鞘翅飞行(米)", clampInt(s.aviate() / 100));
    }

    private static int clampInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static void send(ServerPlayerEntity player, PlayerBoard pb, String label, int value) {
        player.networkHandler.sendPacket(new ScoreboardScoreUpdateS2CPacket(
                label, pb.objective().getName(), value, Optional.empty(), Optional.empty()));
    }
}
