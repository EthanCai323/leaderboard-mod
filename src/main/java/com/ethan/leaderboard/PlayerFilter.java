package com.ethan.leaderboard;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 玩家白名单/黑名单（leaderboard/whitelist.json、blacklist.json，JSON 字符串数组，存玩家名）。
 * 过滤优先级：黑名单 -> 永远排除；白名单 -> 永远包含（即使 bot_ 前缀）；否则 bot_ 前缀排除。
 * 名字比较不区分大小写。
 */
public final class PlayerFilter {
    public static final int RESULT_OK = 0;
    public static final int RESULT_BLOCKED_BY_OTHER_LIST = 1;
    public static final int RESULT_TOGGLED_OFF = 2;

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

    /** 该玩家是否计入排行榜 */
    public static boolean isIncluded(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (blacklist.contains(key)) {
            return false;
        }
        if (whitelist.contains(key)) {
            return true;
        }
        return !key.startsWith(StatFormat.BOT_PREFIX);
    }

    /**
     * /leaderboard player add：加入白名单。
     * RESULT_OK 已加入；RESULT_BLOCKED_BY_OTHER_LIST 已在黑名单（不变更）；
     * RESULT_TOGGLED_OFF 已在白名单，已移出。
     */
    public static int addWhitelist(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (blacklist.contains(key)) {
            return RESULT_BLOCKED_BY_OTHER_LIST;
        }
        if (!whitelist.add(key)) {
            whitelist.remove(key);
            save();
            return RESULT_TOGGLED_OFF;
        }
        save();
        return RESULT_OK;
    }

    /** /leaderboard player remove：加入黑名单（语义同上） */
    public static int addBlacklist(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (whitelist.contains(key)) {
            return RESULT_BLOCKED_BY_OTHER_LIST;
        }
        if (!blacklist.add(key)) {
            blacklist.remove(key);
            save();
            return RESULT_TOGGLED_OFF;
        }
        save();
        return RESULT_OK;
    }
}
