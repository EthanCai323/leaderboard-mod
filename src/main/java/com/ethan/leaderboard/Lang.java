package com.ethan.leaderboard;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 中文翻译表：从服务器运行目录 leaderboard/lang/zh_cn.json 加载（官方 zh_cn 资源）。
 * 文件缺失或解析失败时回退到英文 prettify。
 * 基于文件修改时间懒加载，文件改动后下次访问自动生效。
 */
public final class Lang {
    private static volatile Map<String, String> table = Map.of();
    /** 上次加载时文件的修改时间（毫秒）；Long.MIN_VALUE 表示尚未尝试过加载 */
    private static long lastModified = Long.MIN_VALUE;
    /** 是否已对当前文件状态做过日志提示（避免每次刷新重复刷日志） */
    private static boolean lastStateLogged = false;

    private Lang() {
    }

    /** 文件有变化才重新读取（GUI 打开与排行榜生成时调用） */
    public static synchronized void reloadIfChanged() {
        Path path = langPath();
        try {
            if (!Files.exists(path)) {
                if (!lastStateLogged) {
                    ServerLeaderboardMod.LOGGER.info(
                            "[排行榜] 未找到 {}，物品与生物名称将显示为英文", path);
                    lastStateLogged = true;
                }
                table = Map.of();
                lastModified = -1L;
                return;
            }
            long modified = Files.getLastModifiedTime(path).toMillis();
            if (modified == lastModified) {
                return;
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            // 去掉可能的 UTF-8 BOM，避免解析失败
            if (!content.isEmpty() && content.charAt(0) == '﻿') {
                content = content.substring(1);
            }
            JsonObject obj = JsonParser.parseString(content).getAsJsonObject();
            Map<String, String> map = new HashMap<>();
            for (Map.Entry<String, com.google.gson.JsonElement> e : obj.entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    map.put(e.getKey(), e.getValue().getAsString());
                }
            }
            table = map;
            lastModified = modified;
            if (!lastStateLogged) {
                ServerLeaderboardMod.LOGGER.info("[排行榜] 中文翻译表已加载，共 {} 条", map.size());
                lastStateLogged = true;
            }
        } catch (Exception e) {
            table = Map.of();
            lastModified = -1L;
            if (!lastStateLogged) {
                ServerLeaderboardMod.LOGGER.warn("[排行榜] 读取翻译表失败，物品与生物名称将显示为英文: {}",
                        e.toString());
                lastStateLogged = true;
            }
        }
    }

    private static Path langPath() {
        return FabricLoader.getInstance().getGameDir()
                .resolve("leaderboard").resolve("lang").resolve("zh_cn.json");
    }

    /** 物品类统计名：item.<ns>.<id> -> block.<ns>.<id> -> prettify */
    public static String itemName(String fullId) {
        reloadIfChanged();
        String key = fullId.replace(':', '.');
        String name = table.get("item." + key);
        if (name != null) {
            return name;
        }
        name = table.get("block." + key);
        if (name != null) {
            return name;
        }
        return StatFormat.prettifyId(fullId);
    }

    /** 生物类统计名：entity.<ns>.<id> -> prettify */
    public static String entityName(String fullId) {
        reloadIfChanged();
        String name = table.get("entity." + fullId.replace(':', '.'));
        return name != null ? name : StatFormat.prettifyId(fullId);
    }
}
