package com.ethan.leaderboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 定时执行的核心：读取 world/stats/*.json，排除 bot_ 假人，
 * 计算排行榜并输出 leaderboard.html / leaderboard.json。
 */
public final class LeaderboardGenerator {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static volatile LeaderboardData lastData;
    private static volatile String lastUpdated;
    private static volatile Map<String, Boolean> lastAllPlayers = Map.of();

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

    public static synchronized boolean generate(MinecraftServer server) {
        try {
            // 热加载配置、名单与翻译表
            LeaderboardConfig.load();
            PlayerFilter.load();
            Lang.reload();

            Path statsDir = server.getSavePath(WorldSavePath.ROOT).resolve("stats");
            if (!Files.isDirectory(statsDir)) {
                ServerLeaderboardMod.LOGGER.warn("[排行榜] 找不到统计目录: {}", statsDir);
                return false;
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

            // --- 解析 UUID -> 名字，按白名单/黑名单/bot_ 前缀过滤 ---
            Map<String, String> fileNames = loadUserCacheFile();
            Map<String, Map<String, Map<String, Long>>> allStats = new LinkedHashMap<>();
            Map<String, Boolean> allPlayers = new LinkedHashMap<>();
            List<String> excluded = new ArrayList<>();
            int unknown = 0;
            for (Map.Entry<String, Map<String, Map<String, Long>>> e : raw.entrySet()) {
                String uuidStr = e.getKey();
                String name = resolveName(server, uuidStr, fileNames);
                if (name == null) {
                    name = "未知玩家-" + uuidStr.substring(0, Math.min(8, uuidStr.length()));
                    unknown++;
                }
                boolean included = PlayerFilter.isIncluded(name);
                allPlayers.put(name, included);
                if (!included) {
                    excluded.add(name);
                    continue;
                }
                allStats.put(name, e.getValue());
            }
            lastAllPlayers = allPlayers;
            if (!excluded.isEmpty()) {
                ServerLeaderboardMod.LOGGER.info("[排行榜] 已排除 {} 个玩家: {}", excluded.size(), String.join(", ", excluded));
            }
            if (unknown > 0) {
                ServerLeaderboardMod.LOGGER.info("[排行榜] 有 {} 个 UUID 未找到名字", unknown);
            }
            if (allStats.isEmpty()) {
                ServerLeaderboardMod.LOGGER.info("[排行榜] 没有可统计的玩家，跳过本次生成");
                return false;
            }

            // --- 计算 ---
            LeaderboardData data = LeaderboardCompute.compute(allStats);
            lastData = data;
            String updated = OffsetDateTime.now(ZoneOffset.ofHours(8)).format(TS_FORMAT);
            lastUpdated = updated;

            // --- 输出到服务器运行目录 ---
            Path gameDir = FabricLoader.getInstance().getGameDir();
            Path htmlPath = gameDir.resolve("leaderboard.html");
            Path jsonPath = gameDir.resolve("leaderboard.json");
            Files.writeString(htmlPath, HtmlReport.buildHtml(data, updated), StandardCharsets.UTF_8);
            Files.writeString(jsonPath, buildJson(data, updated), StandardCharsets.UTF_8);

            String top = data.overall.get(0);
            ServerLeaderboardMod.LOGGER.info("[排行榜] 已更新：{} 名玩家，综合第一 {}（{} 分）-> {}",
                    data.overall.size(), top, data.score.get(top), htmlPath);
            return true;
        } catch (Exception e) {
            ServerLeaderboardMod.LOGGER.error("[排行榜] 生成失败", e);
            return false;
        }
    }

    /** 解析 stats JSON：分类 -> 统计项 -> 数值 */
    private static Map<String, Map<String, Long>> parseStats(JsonObject stats) {
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

    /** 名字解析：优先服务器 UserCache，其次 usercache.json 文件 */
    private static String resolveName(MinecraftServer server, String uuidStr, Map<String, String> fileNames) {
        try {
            UUID uuid = UUID.fromString(uuidStr);
            var cache = server.getUserCache();
            if (cache != null) {
                Optional<GameProfile> profile = cache.getByUuid(uuid);
                if (profile.isPresent() && profile.get().getName() != null) {
                    return profile.get().getName();
                }
            }
        } catch (Exception ignored) {
            // UUID 解析失败或缓存查询失败，走文件兜底
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
        root.addProperty("player_count", data.overall.size());

        JsonArray overall = new JsonArray();
        int rank = 0;
        for (String name : data.overall) {
            rank++;
            JsonObject o = new JsonObject();
            o.addProperty("rank", rank);
            o.addProperty("name", name);
            o.addProperty("score", data.score.get(name));
            o.addProperty("titles", data.titles.get(name));
            overall.add(o);
        }
        root.add("overall", overall);

        JsonArray kings = new JsonArray();
        for (LeaderboardData.CustomLeader leader : data.leadersCustom) {
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
        for (Map.Entry<String, List<LeaderboardData.ItemRow>> e : data.leadersItems.entrySet()) {
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
        for (String name : data.overall) {
            LeaderboardData.PlayerSummary ps = data.playerSummary.get(name);
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
