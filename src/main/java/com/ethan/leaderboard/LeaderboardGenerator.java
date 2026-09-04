package com.ethan.leaderboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 定时执行的核心：读取 world/stats/*.json，按名单与名称特征筛除玩家，
 * 计算排行榜并输出 leaderboard.html / leaderboard.json。
 * 文件读取、JSON 解析与排名计算在后台线程执行，
 * 结果写盘与广播通过 server.execute 切回主线程；AtomicBoolean 防止并发重入。
 * 无法解析出名字的 UUID 直接跳过，不参与排名。
 */
public final class LeaderboardGenerator {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "server-leaderboard-generator");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    /** 数据版本号：每次成功生成新数据后递增，供计分板等消费方判断是否需要重发 */
    private static final AtomicLong DATA_VERSION = new AtomicLong(0);

    private static volatile LeaderboardData lastData;
    private static volatile String lastUpdated;
    private static volatile Map<String, Boolean> lastAllPlayers = Map.of();
    /** 本会话已提醒过的孤儿 stats 文件（避免每次生成重复提醒） */
    private static final Set<String> reportedOrphans = ConcurrentHashMap.newKeySet();

    private LeaderboardGenerator() {
    }

    public static LeaderboardData getLastData() {
        return lastData;
    }

    public static String getLastUpdated() {
        return lastUpdated;
    }

    /** 最近一次生成时所有有统计记录的玩家：名字 -> 是否被包含（供 player list all） */
    public static Map<String, Boolean> getLastAllPlayers() {
        return lastAllPlayers;
    }

    /** 是否正在后台生成 */
    public static boolean isRunning() {
        return RUNNING.get();
    }

    /** 数据版本号：每次成功生成递增，初始为 0 */
    public static long getDataVersion() {
        return DATA_VERSION.get();
    }

    /**
     * 服务器停止时优雅关闭后台线程：先等待进行中的生成完成（最多 5 秒），
     * 超时或被中断则强制中断，避免输出文件写一半。
     */
    public static void shutdown() {
        EXECUTOR.shutdown();
        try {
            if (!EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                ServerLeaderboardMod.LOGGER.warn("[排行榜] 后台生成未在 5 秒内完成，强制中断");
                EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 请求一次异步生成。已在生成中时返回 false（不排队）。
     * onDone 在主线程回调，参数表示本次是否成功产出新数据。
     * 注意：必须在服务器主线程调用。
     */
    public static boolean requestGenerate(MinecraftServer server, Consumer<Boolean> onDone) {
        if (!RUNNING.compareAndSet(false, true)) {
            return false;
        }
        // 主线程热加载配置、名单与各名称表
        LeaderboardConfig.load();
        PlayerFilter.load();
        StatFormat.loadStatNamesIfChanged();
        Lang.reloadIfChanged();

        Path statsDir = server.getSavePath(WorldSavePath.ROOT).resolve("stats");
        // 主线程快照在线玩家：UUID -> 名字，以及 Carpet 假人 UUID
        Map<String, String> onlineNames = new HashMap<>();
        Set<String> fakeUuids = new HashSet<>();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            String id = player.getUuid().toString().toLowerCase(Locale.ROOT);
            onlineNames.put(id, player.getGameProfile().getName());
            if (PlayerFilter.isCarpetFakePlayer(player)) {
                fakeUuids.add(id);
            }
        }

        EXECUTOR.submit(() -> {
            OffThreadResult result;
            try {
                result = computeOffThread(statsDir, onlineNames, fakeUuids);
            } catch (Exception e) {
                ServerLeaderboardMod.LOGGER.error("[排行榜] 生成失败", e);
                result = null;
            }
            OffThreadResult finalResult = result;
            server.execute(() -> {
                boolean ok;
                try {
                    ok = finishOnMainThread(finalResult);
                } finally {
                    RUNNING.set(false);
                }
                if (onDone != null) {
                    onDone.accept(ok);
                }
            });
        });
        return true;
    }

    /** 后台线程结果：data 为 null 表示没有可统计玩家或失败 */
    private record OffThreadResult(LeaderboardData data, Map<String, Boolean> allPlayers,
                                   List<String> orphanUuids, String updated) {
    }

    /** 后台线程：读文件、解析、过滤、计算 */
    private static OffThreadResult computeOffThread(Path statsDir, Map<String, String> onlineNames,
                                                    Set<String> fakeUuids) throws IOException {
        if (!Files.isDirectory(statsDir)) {
            ServerLeaderboardMod.LOGGER.warn("[排行榜] 找不到统计目录: {}", statsDir);
            return null;
        }

        // --- 读取全部玩家统计文件 ---
        Map<String, Map<String, Map<String, Long>>> raw = new LinkedHashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(statsDir, "*.json")) {
            for (Path p : stream) {
                String fname = p.getFileName().toString();
                String uuid = fname.substring(0, fname.length() - ".json".length());
                try {
                    JsonObject root = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
                    JsonObject stats = root.has("stats") && root.get("stats").isJsonObject()
                            ? root.getAsJsonObject("stats") : new JsonObject();
                    raw.put(uuid.toLowerCase(Locale.ROOT), parseStats(stats));
                } catch (Exception e) {
                    ServerLeaderboardMod.LOGGER.warn("[排行榜] 读取统计文件失败: {} ({})", fname, e.toString());
                }
            }
        }

        // --- 解析 UUID -> 名字，按名单/名称特征/假人类名过滤 ---
        Map<String, String> fileNames = loadUserCacheFile();
        Map<String, Map<String, Map<String, Long>>> allStats = new LinkedHashMap<>();
        Map<String, String> nameToUuid = new LinkedHashMap<>();
        Map<String, Boolean> allPlayers = new LinkedHashMap<>();
        List<String> excluded = new ArrayList<>();
        List<String> orphans = new ArrayList<>();
        for (Map.Entry<String, Map<String, Map<String, Long>>> e : raw.entrySet()) {
            String uuidStr = e.getKey();
            String name = resolveName(uuidStr, onlineNames, fileNames);
            if (name == null) {
                // 解析不出名字的玩家直接跳过，不参与排名
                orphans.add(uuidStr);
                continue;
            }
            boolean whitelisted = PlayerFilter.isWhitelisted(name);
            boolean included = PlayerFilter.isIncluded(name)
                    && (whitelisted || !fakeUuids.contains(uuidStr));
            allPlayers.put(name, included);
            if (!included) {
                excluded.add(name);
                continue;
            }
            allStats.put(name, e.getValue());
            nameToUuid.put(name, uuidStr);
        }
        if (!excluded.isEmpty()) {
            ServerLeaderboardMod.LOGGER.info("[排行榜] 已排除 {} 个玩家: {}", excluded.size(), String.join(", ", excluded));
        }
        if (allStats.isEmpty()) {
            return new OffThreadResult(null, allPlayers, orphans, null);
        }

        LeaderboardData data = LeaderboardCompute.compute(allStats, nameToUuid);
        String updated = OffsetDateTime.now(ZoneOffset.ofHours(8)).format(TS_FORMAT);
        return new OffThreadResult(data, allPlayers, orphans, updated);
    }

    /** 主线程：落地结果、写文件、记录日志 */
    private static boolean finishOnMainThread(OffThreadResult result) {
        if (result == null) {
            return false;
        }
        lastAllPlayers = result.allPlayers();
        reportOrphans(result.orphanUuids());
        LeaderboardData data = result.data();
        if (data == null) {
            ServerLeaderboardMod.LOGGER.info("[排行榜] 没有可统计的玩家，跳过本次生成");
            return false;
        }
        lastData = data;
        lastUpdated = result.updated();
        DATA_VERSION.incrementAndGet();
        // 把新生成中出现的统计项追加进 custom_display.txt（默认 true），保证旧配置文件可控所有项
        Set<String> statIds = new TreeSet<>();
        for (LeaderboardData.CustomLeader leader : data.getLeadersCustom()) {
            statIds.add(leader.stat());
        }
        for (List<LeaderboardData.ItemRow> rows : data.getLeadersItems().values()) {
            for (LeaderboardData.ItemRow row : rows) {
                statIds.add(row.stat());
            }
        }
        CustomDisplay.appendMissing(statIds);
        try {
            // --- 输出到服务器运行目录 ---
            Path gameDir = FabricLoader.getInstance().getGameDir();
            Path htmlPath = gameDir.resolve("leaderboard.html");
            Path jsonPath = gameDir.resolve("leaderboard.json");
            String json = buildJson(data, result.updated());
            Files.writeString(htmlPath, HtmlReport.buildHtml(data, result.updated()), StandardCharsets.UTF_8);
            Files.writeString(jsonPath, json, StandardCharsets.UTF_8);
            // 历史快照归档：交回后台线程执行，失败只记日志不影响主流程
            if (LeaderboardConfig.get().historyKeep > 0) {
                String updated = result.updated();
                EXECUTOR.submit(() -> archiveSnapshot(gameDir, json, updated));
            }

            String top = data.getOverall().get(0);
            ServerLeaderboardMod.LOGGER.info("[排行榜] 已更新：{} 名玩家，综合第一 {}（{} 分）-> {}",
                    data.getOverall().size(), top, data.getScore().get(top), htmlPath);
            return true;
        } catch (Exception e) {
            ServerLeaderboardMod.LOGGER.error("[排行榜] 写入输出文件失败", e);
            return false;
        }
    }

    /**
     * 后台线程：把本次 leaderboard.json 归档到 leaderboard/history/yyyy-MM-dd_HH-mm-ss.json，
     * 然后清理超出保留数量的最旧快照。任何失败只记日志，不影响生成主流程。
     */
    private static void archiveSnapshot(Path gameDir, String json, String updated) {
        try {
            Path dir = gameDir.resolve("leaderboard").resolve("history");
            Files.createDirectories(dir);
            String ts = updated.replace(':', '-').replace(' ', '_');
            Files.writeString(dir.resolve(ts + ".json"), json, StandardCharsets.UTF_8);
            pruneHistory(dir);
        } catch (Exception e) {
            ServerLeaderboardMod.LOGGER.warn("[排行榜] 历史快照归档失败: {}", e.toString());
        }
    }

    /**
     * 后台线程：按 historyKeep 清理最旧快照，文件名即时间戳，字典序即时间序。
     * historyKeep 为 0 表示关闭归档，不清空已有快照。
     */
    private static void pruneHistory(Path dir) {
        int keep = LeaderboardConfig.get().historyKeep;
        if (keep <= 0) {
            return;
        }
        try {
            List<Path> files = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
                for (Path p : stream) {
                    files.add(p);
                }
            }
            files.sort(Comparator.comparing(p -> p.getFileName().toString()));
            int excess = files.size() - keep;
            for (int i = 0; i < excess; i++) {
                Files.deleteIfExists(files.get(i));
            }
            if (excess > 0) {
                ServerLeaderboardMod.LOGGER.info("[排行榜] 已清理 {} 个超量历史快照", excess);
            }
        } catch (Exception e) {
            ServerLeaderboardMod.LOGGER.warn("[排行榜] 清理历史快照失败: {}", e.toString());
        }
    }

    /** 修改保留数量后在后台立即执行一次清理 */
    public static void pruneHistoryAsync() {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("leaderboard").resolve("history");
        if (!Files.isDirectory(dir)) {
            return;
        }
        EXECUTOR.submit(() -> pruneHistory(dir));
    }

    /** 孤儿 stats 文件提醒：每个 UUID 本会话只提醒一次 */
    private static void reportOrphans(List<String> orphans) {
        List<String> fresh = new ArrayList<>();
        for (String uuid : orphans) {
            if (reportedOrphans.add(uuid)) {
                fresh.add(uuid);
            }
        }
        if (!fresh.isEmpty()) {
            ServerLeaderboardMod.LOGGER.warn(
                    "[排行榜] {} 个 stats 文件无法解析出玩家名，已跳过排名；若为遗留数据，"
                            + "服主可手动删除 world/stats 下对应文件: {}",
                    fresh.size(), String.join(", ", fresh));
        }
    }

    /** 解析 stats JSON：分类 -> 统计项 -> 数值（包内可见，计分板实时刷新复用） */
    static Map<String, Map<String, Long>> parseStats(JsonObject stats) {
        Map<String, Map<String, Long>> out = new LinkedHashMap<>();
        for (Map.Entry<String, com.google.gson.JsonElement> catEntry : stats.entrySet()) {
            if (!catEntry.getValue().isJsonObject()) {
                continue;
            }
            Map<String, Long> cat = new LinkedHashMap<>();
            for (Map.Entry<String, com.google.gson.JsonElement> statEntry
                    : catEntry.getValue().getAsJsonObject().entrySet()) {
                try {
                    cat.put(statEntry.getKey(), statEntry.getValue().getAsLong());
                } catch (Exception ignored) {
                    // 忽略非数值条目
                }
            }
            out.put(catEntry.getKey(), cat);
        }
        return out;
    }

    /** 名字解析：优先在线玩家快照，其次 usercache.json 文件（均为主线程快照/本地文件，无网络请求） */
    private static String resolveName(String uuidStr, Map<String, String> onlineNames,
                                      Map<String, String> fileNames) {
        String name = onlineNames.get(uuidStr.toLowerCase(Locale.ROOT));
        if (name != null) {
            return name;
        }
        return fileNames.get(uuidStr.toLowerCase(Locale.ROOT));
    }

    /** 读取运行目录 usercache.json 作为兜底 */
    private static Map<String, String> loadUserCacheFile() {
        Map<String, String> names = new LinkedHashMap<>();
        Path path = FabricLoader.getInstance().getGameDir().resolve("usercache.json");
        if (!Files.exists(path)) {
            return names;
        }
        try {
            JsonArray arr = JsonParser.parseString(Files.readString(path)).getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                JsonObject entry = arr.get(i).getAsJsonObject();
                if (entry.has("uuid") && entry.has("name")) {
                    names.put(entry.get("uuid").getAsString().toLowerCase(Locale.ROOT),
                            entry.get("name").getAsString());
                }
            }
        } catch (IOException | RuntimeException e) {
            ServerLeaderboardMod.LOGGER.warn("[排行榜] 读取 usercache.json 失败: {}", e.toString());
        }
        return names;
    }

    /** 结构化 JSON 输出 */
    private static String buildJson(LeaderboardData data, String updated) {
        JsonObject root = new JsonObject();
        root.addProperty("updated", updated);
        root.addProperty("player_count", data.getOverall().size());

        JsonArray overall = new JsonArray();
        int rank = 0;
        for (String name : data.getOverall()) {
            rank++;
            JsonObject o = new JsonObject();
            o.addProperty("rank", rank);
            o.addProperty("name", name);
            String uuid = data.getUuids().get(name);
            if (uuid != null) {
                o.addProperty("uuid", uuid);
            }
            o.addProperty("score", data.getScore().get(name));
            o.addProperty("titles", data.getTitles().get(name));
            overall.add(o);
        }
        root.add("overall", overall);

        // 全服 9 项核心数据总和
        long totalPlayTime = 0, totalDeaths = 0, totalMobKills = 0, totalDamageDealt = 0,
                totalDamageTaken = 0, totalDistance = 0, totalMined = 0, totalCrafted = 0, totalAviate = 0;
        for (LeaderboardData.PlayerSummary ps : data.getPlayerSummary().values()) {
            totalPlayTime += ps.playTime();
            totalDeaths += ps.deaths();
            totalMobKills += ps.mobKills();
            totalDamageDealt += ps.damageDealt();
            totalDamageTaken += ps.damageTaken();
            totalDistance += ps.distance();
            totalMined += ps.minedTotal();
            totalCrafted += ps.craftedTotal();
            totalAviate += ps.aviate();
        }
        JsonObject totals = new JsonObject();
        totals.addProperty("total_play_time", totalPlayTime);
        totals.addProperty("total_deaths", totalDeaths);
        totals.addProperty("total_mob_kills", totalMobKills);
        totals.addProperty("total_damage_dealt", totalDamageDealt);
        totals.addProperty("total_damage_taken", totalDamageTaken);
        totals.addProperty("total_distance", totalDistance);
        totals.addProperty("total_mined", totalMined);
        totals.addProperty("total_crafted", totalCrafted);
        totals.addProperty("total_aviate", totalAviate);
        root.add("server_totals", totals);

        JsonArray kings = new JsonArray();
        for (LeaderboardData.CustomLeader leader : data.getLeadersCustom()) {
            LeaderboardData.Entry top = leader.ranking().get(0);
            JsonObject o = new JsonObject();
            o.addProperty("stat", leader.stat());
            o.addProperty("stat_name", StatFormat.statDisplayName(leader.stat()));
            o.addProperty("king", top.name());
            o.addProperty("value", top.value());
            o.addProperty("formatted", StatFormat.formatValue(leader.stat(), top.value()));
            kings.add(o);
        }
        root.add("kings", kings);

        JsonObject cats = new JsonObject();
        for (Map.Entry<String, List<LeaderboardData.ItemRow>> e : data.getLeadersItems().entrySet()) {
            JsonArray rows = new JsonArray();
            List<LeaderboardData.ItemRow> catRows = e.getValue();
            int limit = Math.min(StatFormat.TOP_ITEMS_PER_CATEGORY, catRows.size());
            for (LeaderboardData.ItemRow row : catRows.subList(0, limit)) {
                LeaderboardData.Entry top = row.ranking().get(0);
                JsonObject o = new JsonObject();
                o.addProperty("item", row.stat());
                o.addProperty("item_name", StatFormat.prettifyId(row.stat()));
                o.addProperty("king", top.name());
                o.addProperty("value", top.value());
                o.addProperty("total", row.total());
                rows.add(o);
            }
            cats.add(e.getKey(), rows);
        }
        root.add("item_categories", cats);

        JsonArray players = new JsonArray();
        for (String name : data.getOverall()) {
            LeaderboardData.PlayerSummary ps = data.getPlayerSummary().get(name);
            JsonObject o = new JsonObject();
            o.addProperty("name", name);
            o.addProperty("play_time", ps.playTime());
            o.addProperty("deaths", ps.deaths());
            o.addProperty("mob_kills", ps.mobKills());
            o.addProperty("damage_dealt", ps.damageDealt());
            o.addProperty("damage_taken", ps.damageTaken());
            o.addProperty("distance", ps.distance());
            o.addProperty("mined_total", ps.minedTotal());
            o.addProperty("crafted_total", ps.craftedTotal());
            o.addProperty("aviate", ps.aviate());
            players.add(o);
        }
        root.add("players", players);

        return GSON.toJson(root);
    }
}
