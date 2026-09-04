package com.ethan.leaderboard;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 自定义显示模式配置（leaderboard/custom_display.txt）。
 * 每行 "stat_id true/false"，# 开头为注释；false 的条目在 GUI 通用榜与各明细视图中隐藏。
 * 仅影响 custom 模式。基于文件修改时间懒加载。
 */
public final class CustomDisplay {
    private static volatile Map<String, Boolean> entries = Map.of();
    /** 上次加载时文件的修改时间（毫秒）；Long.MIN_VALUE 表示尚未尝试过加载 */
    private static long lastModified = Long.MIN_VALUE;

    private CustomDisplay() {
    }

    public static Path path() {
        return FabricLoader.getInstance().getGameDir().resolve("leaderboard").resolve("custom_display.txt");
    }

    public static boolean fileExists() {
        return Files.exists(path());
    }

    /** 文件有变化才重新读取（文件不存在时清空，视为全部显示） */
    public static synchronized void loadIfChanged() {
        if (!fileExists()) {
            entries = Map.of();
            lastModified = -1L;
            return;
        }
        try {
            long modified = Files.getLastModifiedTime(path()).toMillis();
            if (modified == lastModified) {
                return;
            }
            Map<String, Boolean> map = new HashMap<>();
            List<String> lines = Files.readAllLines(path());
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 2) {
                    map.put(parts[0], Boolean.parseBoolean(parts[1]));
                }
            }
            entries = map;
            lastModified = modified;
        } catch (Exception e) {
            ServerLeaderboardMod.LOGGER.warn("[排行榜] 读取 custom_display.txt 失败: {}", e.toString());
            lastModified = -1L;
        }
    }

    public static boolean isShown(String statId) {
        return entries.getOrDefault(statId, true);
    }

    /** 首次切换到 custom 模式时写入全部已知条目的默认 true 版本 */
    public static void writeDefaults(Collection<String> statIds) {
        try {
            Files.createDirectories(path().getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("# 排行榜自定义显示配置\n");
            sb.append("# 每行格式：stat_id true/false，false 表示在 GUI 中隐藏该条目\n");
            for (String id : new TreeSet<>(statIds)) {
                sb.append(id).append(" true\n");
            }
            Files.writeString(path(), sb.toString());
            lastModified = -1L;
        } catch (Exception e) {
            ServerLeaderboardMod.LOGGER.warn("[排行榜] 写入 custom_display.txt 失败: {}", e.toString());
        }
    }
}
