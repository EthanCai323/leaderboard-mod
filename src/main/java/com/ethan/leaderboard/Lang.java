package com.ethan.leaderboard;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 中文翻译表：从服务器运行目录 leaderboard/lang/zh_cn.json 加载（官方 zh_cn 资源）。
 * 文件缺失或解析失败时回退到英文 prettify。
 */
public final class Lang {
    private static volatile Map<String, String> table = Map.of();

    private Lang() {
    }

    /** 重新加载翻译表（每次生成排行榜时调用，支持热更新文件） */
    public static void reload() {
        Path path = langPath();
        if (!Files.exists(path)) {
            table = Map.of();
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            Map<String, String> map = new HashMap<>();
            for (Map.Entry<String, com.google.gson.JsonElement> e : obj.entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    map.put(e.getKey(), e.getValue().getAsString());
                }
            }
            table = map;
        } catch (Exception e) {
            ServerLeaderboardMod.LOGGER.warn("[排行榜] 读取翻译表失败: {}", e.toString());
            table = Map.of();
        }
    }

    private static Path langPath() {
        return FabricLoader.getInstance().getGameDir()
                .resolve("leaderboard").resolve("lang").resolve("zh_cn.json");
    }

    /** 物品类统计名：item.<ns>.<id> -> block.<ns>.<id> -> prettify */
    public static String itemName(String fullId) {
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
        String name = table.get("entity." + fullId.replace(':', '.'));
        return name != null ? name : StatFormat.prettifyId(fullId);
    }
}
