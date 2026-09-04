package com.ethan.leaderboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * leaderboard/config.json 持久化：显示模式、自动刷新间隔、广播开关、
 * 侧边计分板开关与玩家筛除规则。
 * 带 version 字段，旧版本配置文件缺字段时自动补默认值。
 */
public class LeaderboardConfig {
    public static final String MODE_COMPACT = "compact";
    public static final String MODE_NORMAL = "normal";
    public static final String MODE_FULL = "full";
    public static final String MODE_CUSTOM = "custom";
    public static final long DEFAULT_INTERVAL_TICKS = 72000L;

    /** 当前配置文件版本，加载旧版本文件时按需迁移 */
    public static final int CURRENT_VERSION = 4;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static LeaderboardConfig instance = new LeaderboardConfig();

    public int version = CURRENT_VERSION;
    public String displayMode = MODE_NORMAL;
    public long refreshIntervalTicks = DEFAULT_INTERVAL_TICKS;
    /** 自动刷新后是否在聊天栏广播更新提示 */
    public boolean broadcastRefresh = true;
    /** 是否允许普通玩家开启个人侧边计分板（OP 不受限制） */
    public boolean allowScoreboard = true;
    /** 历史快照保留数量，0 表示关闭归档 */
    public int historyKeep = 30;
    /** 计分板主动刷新间隔（tick），0 表示跟随排行榜数据更新 */
    public long scoreboardRefreshIntervalTicks = 0L;
    /** 筛除玩家的名称前缀（默认排除 bot_ 假人） */
    public List<String> screenPrefixes = new ArrayList<>(List.of(StatFormat.BOT_PREFIX));
    /** 筛除玩家的名称后缀 */
    public List<String> screenSuffixes = new ArrayList<>();

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
                    loaded.migrate();
                    instance = loaded;
                }
            } catch (Exception e) {
                ServerLeaderboardMod.LOGGER.warn("[排行榜] 读取 config.json 失败，使用默认值: {}", e.toString());
                instance = new LeaderboardConfig();
            }
        }
        save();
    }

    /** 旧版本配置迁移：补齐缺失字段的默认值，并升级版本号 */
    private void migrate() {
        if (!isValidMode(displayMode)) {
            displayMode = MODE_NORMAL;
        }
        // Gson 反序列化时缺失的 List 字段会保持为 null（绕过字段初始化器），这里补默认值
        if (screenPrefixes == null) {
            screenPrefixes = new ArrayList<>(List.of(StatFormat.BOT_PREFIX));
        }
        if (screenSuffixes == null) {
            screenSuffixes = new ArrayList<>();
        }
        // v2 之前的配置没有 historyKeep，Gson 会反序列化为 0（关闭），补回默认值
        if (version < 3) {
            historyKeep = 30;
        }
        if (historyKeep < 0) {
            historyKeep = 0;
        }
        // 缺失的 long 字段反序列化为 0，即"跟随排行榜数据更新"，正好是默认值
        if (scoreboardRefreshIntervalTicks < 0) {
            scoreboardRefreshIntervalTicks = 0;
        }
        if (version < CURRENT_VERSION) {
            ServerLeaderboardMod.LOGGER.info("[排行榜] config.json 由版本 {} 迁移至 {}", version, CURRENT_VERSION);
            version = CURRENT_VERSION;
        }
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
