package com.ethan.leaderboard;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 玩家白名单/黑名单（leaderboard/whitelist.json、blacklist.json，JSON 字符串数组，存玩家名）。
 * 过滤优先级：黑名单 -> 永远排除；白名单 -> 永远包含；
 * 否则按 config.json 的名称前缀/后缀特征筛除（默认前缀 bot_）。
 * 名字比较不区分大小写。
 */
public final class PlayerFilter {
    /** 添加成功 */
    public static final int ADD_OK = 0;
    /** 添加成功，且已自动从另一份名单移出 */
    public static final int ADD_MOVED_FROM_OTHER = 1;
    /** 已在该名单中，未变更 */
    public static final int ADD_ALREADY = 2;
    /** 移出成功 */
    public static final int REMOVE_OK = 0;
    /** 不在该名单中，未变更 */
    public static final int REMOVE_NOT_FOUND = 1;

    private static Set<String> whitelist = new LinkedHashSet<>();
    private static Set<String> blacklist = new LinkedHashSet<>();

    private PlayerFilter() {
    }

    public static void load() {
        whitelist = readNameSet("whitelist.json");
        blacklist = readNameSet("blacklist.json");
    }

    private static Set<String> readNameSet(String fileName) {
        Path path = dir().resolve(fileName);
        if (!Files.exists(path)) {
            writeNameSet(path, new LinkedHashSet<>());
            return new LinkedHashSet<>();
        }
        Set<String> names = new LinkedHashSet<>();
        try {
            JsonArray arr = JsonParser.parseString(Files.readString(path)).getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                names.add(arr.get(i).getAsString().toLowerCase(Locale.ROOT));
            }
        } catch (Exception e) {
            ServerLeaderboardMod.LOGGER.warn("[排行榜] 读取 {} 失败: {}", fileName, e.toString());
        }
        return names;
    }

    private static void writeNameSet(Path path, Set<String> names) {
        try {
            Files.createDirectories(path.getParent());
            JsonArray arr = new JsonArray();
            for (String name : names) {
                arr.add(name);
            }
            Files.writeString(path, arr.toString());
        } catch (Exception e) {
            ServerLeaderboardMod.LOGGER.warn("[排行榜] 写入 {} 失败: {}", path, e.toString());
        }
    }

    private static Path dir() {
        return FabricLoader.getInstance().getGameDir().resolve("leaderboard");
    }

    private static void save() {
        writeNameSet(dir().resolve("whitelist.json"), whitelist);
        writeNameSet(dir().resolve("blacklist.json"), blacklist);
    }

    public static boolean isWhitelisted(String name) {
        return whitelist.contains(name.toLowerCase(Locale.ROOT));
    }

    /** 该玩家是否计入排行榜 */
    public static boolean isIncluded(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (blacklist.contains(key)) {
            return false;
        }
        if (whitelist.contains(key)) {
            return true;
        }
        LeaderboardConfig config = LeaderboardConfig.get();
        for (String prefix : config.screenPrefixes) {
            if (!prefix.isEmpty() && key.startsWith(prefix.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        for (String suffix : config.screenSuffixes) {
            if (!suffix.isEmpty() && key.endsWith(suffix.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 是否 Carpet 假人（按类名检测，避免硬依赖 Carpet）。
     * 用于排除类名含 FakePlayer 的在线假人，即使名字不带筛除特征。
     */
    public static boolean isCarpetFakePlayer(ServerPlayerEntity player) {
        String className = player.getClass().getSimpleName();
        return className.contains("FakePlayer");
    }

    /**
     * 加入白名单；若已在黑名单则自动移到白名单。
     * 返回 ADD_OK / ADD_MOVED_FROM_OTHER / ADD_ALREADY。
     */
    public static int whitelistAdd(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (whitelist.contains(key)) {
            return ADD_ALREADY;
        }
        boolean moved = blacklist.remove(key);
        whitelist.add(key);
        save();
        return moved ? ADD_MOVED_FROM_OTHER : ADD_OK;
    }

    /** 移出白名单。返回 REMOVE_OK / REMOVE_NOT_FOUND */
    public static int whitelistRemove(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (!whitelist.remove(key)) {
            return REMOVE_NOT_FOUND;
        }
        save();
        return REMOVE_OK;
    }

    /**
     * 加入黑名单；若已在白名单则自动移到黑名单。
     * 返回 ADD_OK / ADD_MOVED_FROM_OTHER / ADD_ALREADY。
     */
    public static int blacklistAdd(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (blacklist.contains(key)) {
            return ADD_ALREADY;
        }
        boolean moved = whitelist.remove(key);
        blacklist.add(key);
        save();
        return moved ? ADD_MOVED_FROM_OTHER : ADD_OK;
    }

    /** 移出黑名单。返回 REMOVE_OK / REMOVE_NOT_FOUND */
    public static int blacklistRemove(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (!blacklist.remove(key)) {
            return REMOVE_NOT_FOUND;
        }
        save();
        return REMOVE_OK;
    }

    public static List<String> whitelistSnapshot() {
        return List.copyOf(whitelist);
    }

    public static List<String> blacklistSnapshot() {
        return List.copyOf(blacklist);
    }
}
