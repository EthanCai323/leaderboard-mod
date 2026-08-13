package com.ethan.leaderboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * leaderboard/config.json 持久化：显示模式 + 自动刷新间隔（tick）。
 * 首次运行写入默认值。
 */
public class LeaderboardConfig {
    public static final String MODE_COMPACT = "compact";
    public static final String MODE_NORMAL = "normal";
    public static final String MODE_FULL = "full";
    public static final String MODE_CUSTOM = "custom";
    public static final long DEFAULT_INTERVAL_TICKS = 72000L;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static LeaderboardConfig instance = new LeaderboardConfig();

    public String displayMode = MODE_NORMAL;
    public long refreshIntervalTicks = DEFAULT_INTERVAL_TICKS;

    public static LeaderboardConfig get() {
        return instance;
    }

    public static boolean isValidMode(String mode) {
        return MODE_COMPACT.equals(mode) || MODE_NORMAL.equals(mode)
                || MODE_FULL.equals(mode) || MODE_CUSTOM.equals(mode);
    }

    public static void load() {
        Path path = configPath();
        if (Files.exists(path)) {
            try {
                LeaderboardConfig loaded = GSON.fromJson(Files.readString(path), LeaderboardConfig.class);
                if (loaded != null) {
                    if (!isValidMode(loaded.displayMode)) {
                        loaded.displayMode = MODE_NORMAL;
                    }
                    instance = loaded;
                }
            } catch (Exception e) {
                ServerLeaderboardMod.LOGGER.warn("[排行榜] 读取 config.json 失败，使用默认值: {}", e.toString());
                instance = new LeaderboardConfig();
            }
        }
        save();
    }

    public static void save() {
        try {
            Files.createDirectories(configPath().getParent());
            Files.writeString(configPath(), GSON.toJson(instance));
        } catch (Exception e) {
            ServerLeaderboardMod.LOGGER.warn("[排行榜] 写入 config.json 失败: {}", e.toString());
        }
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getGameDir().resolve("leaderboard").resolve("config.json");
    }
}
