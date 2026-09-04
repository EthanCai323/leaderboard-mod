package com.ethan.leaderboard;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandSource;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * /leaderboard 指令树：
 * /leaderboard                                玩家开 GUI；控制台/RCON 输出聊天文字版
 * /leaderboard refresh                        手动刷新（仅 OP，后台异步生成）
 * /leaderboard refresh interval <arg>         自动刷新间隔（仅 OP）
 * /leaderboard refresh broadcast true/false   是否广播自动刷新的聊天提示（仅 OP）
 * /leaderboard mode <模式>                    显示模式（仅 OP）
 * /leaderboard player list [all]              玩家名单（仅 OP）
 * /leaderboard player whitelist add/remove <> 白名单管理（仅 OP）
 * /leaderboard player blacklist add/remove <> 黑名单管理（仅 OP）
 * /leaderboard screen prefix/suffix <特征>    添加名称筛除特征（仅 OP）
 * /leaderboard screen remove <特征>           移除名称筛除特征（仅 OP）
 * /leaderboard screen list                    查看名称筛除特征（仅 OP）
 * /leaderboard allowscoreboard true/false     是否允许普通玩家开启侧边计分板（仅 OP）
 * /leaderboard scoreboard on|off              个人侧边计分板（任何玩家）
 * /leaderboard help                           指令帮助（任何玩家；OP 追加显示管理指令）
 */
public final class LeaderboardCommands {
    private static final Pattern INTERVAL_PATTERN = Pattern.compile("^(\\d+)(t|s|m|h)?$");
    private static final String[] MODES = {"compact", "normal", "full", "custom"};

    private LeaderboardCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(literal("leaderboard")
                        .executes(ctx -> openLeaderboard(ctx.getSource()))
                        .then(literal("refresh")
                                .executes(ctx -> refresh(ctx.getSource()))
                                .then(literal("interval")
                                        .then(argument("value", StringArgumentType.word())
                                                .executes(ctx -> setInterval(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "value")))))
                                .then(literal("broadcast")
                                        .then(argument("enabled", BoolArgumentType.bool())
                                                .executes(ctx -> setBroadcast(ctx.getSource(),
                                                        BoolArgumentType.getBool(ctx, "enabled"))))))
                        .then(literal("mode")
                                .then(argument("mode", StringArgumentType.word())
                                        .suggests((ctx, builder) -> CommandSource.suggestMatching(MODES, builder))
                                        .executes(ctx -> setMode(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "mode")))))
                        .then(literal("player")
                                .then(literal("list")
                                        .executes(ctx -> playerList(ctx.getSource(), false))
                                        .then(literal("all")
                                                .executes(ctx -> playerList(ctx.getSource(), true))))
                                .then(literal("whitelist")
                                        .then(literal("add")
                                                .then(argument("name", StringArgumentType.word())
                                                        .executes(ctx -> whitelistAdd(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "name")))))
                                        .then(literal("remove")
                                                .then(argument("name", StringArgumentType.word())
                                                        .executes(ctx -> whitelistRemove(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "name"))))))
                                .then(literal("blacklist")
                                        .then(literal("add")
                                                .then(argument("name", StringArgumentType.word())
                                                        .executes(ctx -> blacklistAdd(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "name")))))
                                        .then(literal("remove")
                                                .then(argument("name", StringArgumentType.word())
                                                        .executes(ctx -> blacklistRemove(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "name")))))))
                        .then(literal("screen")
                                .then(literal("prefix")
                                        .then(argument("value", StringArgumentType.word())
                                                .executes(ctx -> screenAdd(ctx.getSource(), true,
                                                        StringArgumentType.getString(ctx, "value")))))
                                .then(literal("suffix")
                                        .then(argument("value", StringArgumentType.word())
                                                .executes(ctx -> screenAdd(ctx.getSource(), false,
                                                        StringArgumentType.getString(ctx, "value")))))
                                .then(literal("remove")
                                        .then(argument("value", StringArgumentType.word())
                                                .executes(ctx -> screenRemove(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "value")))))
                                .then(literal("list")
                                        .executes(ctx -> screenList(ctx.getSource()))))
                        .then(literal("allowscoreboard")
                                .then(argument("enabled", BoolArgumentType.bool())
                                        .executes(ctx -> setAllowScoreboard(ctx.getSource(),
                                                BoolArgumentType.getBool(ctx, "enabled")))))
                        .then(literal("help").executes(ctx -> help(ctx.getSource())))
                        .then(literal("scoreboard")
                                .then(literal("on")
                                        .executes(ctx -> scoreboard(ctx.getSource(), true)))
                                .then(literal("off")
                                        .executes(ctx -> scoreboard(ctx.getSource(), false))))));
    }

    /** OP 检查：非 OP 提示红色错误 */
    private static boolean notOp(ServerCommandSource src) {
        if (src.getPermissions().hasPermission(new Permission.Level(PermissionLevel.fromLevel(2)))) {
            return false;
        }
        src.sendMessage(Text.literal("仅 OP 可用此指令").formatted(Formatting.RED));
        return true;
    }

    /**
     * 获取最近一次生成的数据；无数据时触发一次后台生成并返回 null。
     * 调用方需处理 null（提示玩家稍后再试）。
     */
    private static LeaderboardData ensureData(ServerCommandSource src) {
        LeaderboardData data = LeaderboardGenerator.getLastData();
        if (data == null) {
            LeaderboardGenerator.requestGenerate(src.getServer(), null);
        }
        return data;
    }

    /** 名单或筛除规则变更后触发一次后台重新生成 */
    private static void regenerateQuietly(ServerCommandSource src) {
        LeaderboardGenerator.requestGenerate(src.getServer(), null);
    }

    // ---------- /leaderboard ----------

    /** 打开 GUI；非玩家来源回退到聊天文字版 */
    private static int openLeaderboard(ServerCommandSource src) {
        LeaderboardData data = ensureData(src);
        if (data == null || data.isEmpty()) {
            src.sendMessage(Text.literal("暂无排行榜数据，正在后台生成，请稍后再试").formatted(Formatting.RED));
            return 0;
        }
        if (src.getEntity() instanceof ServerPlayerEntity player) {
            LeaderboardGui.open(player, data);
            return 1;
        }
        return showOverallChat(src, data);
    }

    /** 聊天文字版（控制台/RCON 兜底） */
    private static int showOverallChat(ServerCommandSource src, LeaderboardData data) {
        src.sendMessage(Text.literal("—— 综合排行榜 TOP 10 ——").formatted(Formatting.GOLD));
        int limit = Math.min(10, data.getOverall().size());
        for (int i = 0; i < limit; i++) {
            String name = data.getOverall().get(i);
            int rank = i + 1;
            Text line = Text.literal("#" + rank + " " + name)
                    .formatted(rank <= 3 ? Formatting.GOLD : Formatting.GREEN)
                    .append(Text.literal(" - " + data.getScore().get(name) + " 分，冠军 "
                            + data.getTitles().get(name) + " 项")
                            .formatted(Formatting.GREEN));
            src.sendMessage(line);
        }
        return 1;
    }

    // ---------- refresh ----------

    private static int refresh(ServerCommandSource src) {
        if (notOp(src)) {
            return 0;
        }
        boolean started = LeaderboardGenerator.requestGenerate(src.getServer(), ok -> {
            if (ok) {
                LeaderboardData data = LeaderboardGenerator.getLastData();
                String top = data.getOverall().get(0);
                src.sendFeedback(() -> Text.literal("排行榜已重新生成，综合第一：" + top
                        + "（" + data.getScore().get(top) + " 分）").formatted(Formatting.GREEN), true);
            } else {
                src.sendError(Text.literal("排行榜生成失败，请查看服务端日志"));
            }
        });
        if (!started) {
            src.sendMessage(Text.literal("上一次生成尚未完成，请稍候").formatted(Formatting.RED));
            return 0;
        }
        src.sendMessage(Text.literal("正在后台生成排行榜").formatted(Formatting.GRAY));
        return 1;
    }

    // ---------- refresh interval ----------

    private static int setInterval(ServerCommandSource src, String arg) {
        if (notOp(src)) {
            return 0;
        }
        Matcher matcher = INTERVAL_PATTERN.matcher(arg);
        if (!matcher.matches()) {
            src.sendMessage(Text.literal("用法：/leaderboard refresh interval <数字>[t|s|m|h]")
                    .formatted(Formatting.RED));
            return 0;
        }
        long value = Long.parseLong(matcher.group(1));
        String unit = matcher.group(2) == null ? "s" : matcher.group(2);
        long multiplier = switch (unit) {
            case "t" -> 1L;
            case "m" -> 1200L;
            case "h" -> 72000L;
            default -> 20L;
        };
        long ticks = value * multiplier;
        LeaderboardConfig.get().refreshIntervalTicks = ticks;
        LeaderboardConfig.save();
        ServerLeaderboardMod.resetSchedule();
        if (ticks == 0) {
            src.sendMessage(Text.literal("已关闭自动刷新").formatted(Formatting.GREEN));
        } else {
            src.sendMessage(Text.literal("自动刷新间隔已设置为 " + formatSeconds(ticks)
                    + " 秒（" + ticks + " tick）").formatted(Formatting.GREEN));
        }
        return 1;
    }

    private static String formatSeconds(long ticks) {
        if (ticks % 20 == 0) {
            return String.valueOf(ticks / 20);
        }
        return String.format(Locale.US, "%.1f", ticks / 20.0);
    }

    // ---------- refresh broadcast ----------

    private static int setBroadcast(ServerCommandSource src, boolean enabled) {
        if (notOp(src)) {
            return 0;
        }
        LeaderboardConfig.get().broadcastRefresh = enabled;
        LeaderboardConfig.save();
        src.sendMessage(Text.literal(enabled ? "已开启自动刷新广播" : "已关闭自动刷新广播")
                .formatted(Formatting.GREEN));
        return 1;
    }

    // ---------- mode ----------

    private static int setMode(ServerCommandSource src, String mode) {
        if (notOp(src)) {
            return 0;
        }
        if (!LeaderboardConfig.isValidMode(mode)) {
            src.sendMessage(Text.literal("用法：/leaderboard mode <compact|normal|full|custom>")
                    .formatted(Formatting.RED));
            return 0;
        }
        LeaderboardConfig.get().displayMode = mode;
        LeaderboardConfig.save();
        if (LeaderboardConfig.MODE_CUSTOM.equals(mode) && !CustomDisplay.fileExists()) {
            LeaderboardData data = ensureData(src);
            if (data != null) {
                Set<String> ids = new TreeSet<>();
                for (LeaderboardData.CustomLeader leader : data.getLeadersCustom()) {
                    ids.add(leader.stat());
                }
                for (List<LeaderboardData.ItemRow> rows : data.getLeadersItems().values()) {
                    for (LeaderboardData.ItemRow row : rows) {
                        ids.add(row.stat());
                    }
                }
                CustomDisplay.writeDefaults(ids);
            }
        }
        CustomDisplay.loadIfChanged();
        String label = switch (mode) {
            case LeaderboardConfig.MODE_COMPACT -> "精简";
            case LeaderboardConfig.MODE_FULL -> "全部";
            case LeaderboardConfig.MODE_CUSTOM -> "自定义";
            default -> "普通";
        };
        src.sendMessage(Text.literal("已切换至" + label + "模式").formatted(Formatting.GREEN));
        return 1;
    }

    // ---------- player ----------

    private static int playerList(ServerCommandSource src, boolean all) {
        if (notOp(src)) {
            return 0;
        }
        if (all) {
            Map<String, Boolean> allPlayers = LeaderboardGenerator.getLastAllPlayers();
            if (allPlayers.isEmpty()) {
                LeaderboardGenerator.requestGenerate(src.getServer(), null);
                src.sendMessage(Text.literal("暂无排行榜数据，正在后台生成，请稍后再试").formatted(Formatting.RED));
                return 0;
            }
            src.sendMessage(Text.literal("—— 全部有统计记录的玩家（共 " + allPlayers.size() + " 人）——")
                    .formatted(Formatting.GOLD));
            for (Map.Entry<String, Boolean> e : allPlayers.entrySet()) {
                if (e.getValue()) {
                    src.sendMessage(Text.literal(e.getKey() + "（包含）").formatted(Formatting.GREEN));
                } else {
                    src.sendMessage(Text.literal(e.getKey() + "（排除）").formatted(Formatting.GRAY));
                }
            }
            return 1;
        }
        LeaderboardData data = ensureData(src);
        if (data == null || data.isEmpty()) {
            src.sendMessage(Text.literal("暂无排行榜数据，正在后台生成，请稍后再试").formatted(Formatting.RED));
            return 0;
        }
        src.sendMessage(Text.literal("—— 排行榜包含的玩家（共 " + data.getOverall().size() + " 人）——")
                .formatted(Formatting.GOLD));
        for (String name : data.getOverall()) {
            src.sendMessage(Text.literal(name).formatted(Formatting.GREEN));
        }
        return 1;
    }

    private static int whitelistAdd(ServerCommandSource src, String name) {
        if (notOp(src)) {
            return 0;
        }
        int result = PlayerFilter.whitelistAdd(name);
        switch (result) {
            case PlayerFilter.ADD_MOVED_FROM_OTHER ->
                    src.sendMessage(Text.literal("已将" + name + "从黑名单移至白名单").formatted(Formatting.GREEN));
            case PlayerFilter.ADD_ALREADY ->
                    src.sendMessage(Text.literal(name + "已在白名单中").formatted(Formatting.YELLOW));
            default ->
                    src.sendMessage(Text.literal("已将" + name + "添加至白名单").formatted(Formatting.GREEN));
        }
        if (result != PlayerFilter.ADD_ALREADY) {
            regenerateQuietly(src);
        }
        return 1;
    }

    private static int whitelistRemove(ServerCommandSource src, String name) {
        if (notOp(src)) {
            return 0;
        }
        if (PlayerFilter.whitelistRemove(name) == PlayerFilter.REMOVE_NOT_FOUND) {
            src.sendMessage(Text.literal(name + "不在白名单中").formatted(Formatting.RED));
            return 0;
        }
        src.sendMessage(Text.literal("已将" + name + "移出白名单").formatted(Formatting.GREEN));
        regenerateQuietly(src);
        return 1;
    }

    private static int blacklistAdd(ServerCommandSource src, String name) {
        if (notOp(src)) {
            return 0;
        }
        int result = PlayerFilter.blacklistAdd(name);
        switch (result) {
            case PlayerFilter.ADD_MOVED_FROM_OTHER ->
                    src.sendMessage(Text.literal("已将" + name + "从白名单移至黑名单").formatted(Formatting.GREEN));
            case PlayerFilter.ADD_ALREADY ->
                    src.sendMessage(Text.literal(name + "已在黑名单中").formatted(Formatting.YELLOW));
            default ->
                    src.sendMessage(Text.literal("已将" + name + "添加至黑名单").formatted(Formatting.GREEN));
        }
        if (result != PlayerFilter.ADD_ALREADY) {
            regenerateQuietly(src);
        }
        return 1;
    }

    private static int blacklistRemove(ServerCommandSource src, String name) {
        if (notOp(src)) {
            return 0;
        }
        if (PlayerFilter.blacklistRemove(name) == PlayerFilter.REMOVE_NOT_FOUND) {
            src.sendMessage(Text.literal(name + "不在黑名单中").formatted(Formatting.RED));
            return 0;
        }
        src.sendMessage(Text.literal("已将" + name + "移出黑名单").formatted(Formatting.GREEN));
        regenerateQuietly(src);
        return 1;
    }

    // ---------- screen ----------

    private static int screenAdd(ServerCommandSource src, boolean prefix, String value) {
        if (notOp(src)) {
            return 0;
        }
        List<String> list = prefix
                ? LeaderboardConfig.get().screenPrefixes
                : LeaderboardConfig.get().screenSuffixes;
        String kind = prefix ? "前缀" : "后缀";
        String key = value.toLowerCase(Locale.ROOT);
        for (String existing : list) {
            if (existing.equalsIgnoreCase(key)) {
                src.sendMessage(Text.literal("筛除" + kind + " " + value + " 已存在").formatted(Formatting.YELLOW));
                return 0;
            }
        }
        list.add(value);
        LeaderboardConfig.save();
        src.sendMessage(Text.literal("已添加筛除" + kind + "：" + value).formatted(Formatting.GREEN));
        regenerateQuietly(src);
        return 1;
    }

    private static int screenRemove(ServerCommandSource src, String value) {
        if (notOp(src)) {
            return 0;
        }
        String key = value.toLowerCase(Locale.ROOT);
        LeaderboardConfig config = LeaderboardConfig.get();
        boolean removed = config.screenPrefixes.removeIf(s -> s.toLowerCase(Locale.ROOT).equals(key))
                | config.screenSuffixes.removeIf(s -> s.toLowerCase(Locale.ROOT).equals(key));
        if (!removed) {
            src.sendMessage(Text.literal("筛除特征 " + value + " 不存在").formatted(Formatting.RED));
            return 0;
        }
        LeaderboardConfig.save();
        src.sendMessage(Text.literal("已移除筛除特征：" + value).formatted(Formatting.GREEN));
        regenerateQuietly(src);
        return 1;
    }

    private static int screenList(ServerCommandSource src) {
        if (notOp(src)) {
            return 0;
        }
        LeaderboardConfig config = LeaderboardConfig.get();
        src.sendMessage(Text.literal("—— 名称筛除特征 ——").formatted(Formatting.GOLD));
        src.sendMessage(Text.literal("前缀：" + (config.screenPrefixes.isEmpty()
                        ? "无" : String.join("、", config.screenPrefixes)))
                .formatted(Formatting.GRAY));
        src.sendMessage(Text.literal("后缀：" + (config.screenSuffixes.isEmpty()
                        ? "无" : String.join("、", config.screenSuffixes)))
                .formatted(Formatting.GRAY));
        return 1;
    }

    // ---------- allowscoreboard ----------

    private static int setAllowScoreboard(ServerCommandSource src, boolean enabled) {
        if (notOp(src)) {
            return 0;
        }
        LeaderboardConfig.get().allowScoreboard = enabled;
        LeaderboardConfig.save();
        if (enabled) {
            src.sendMessage(Text.literal("已允许普通玩家开启侧边计分板").formatted(Formatting.GREEN));
        } else {
            SidebarScoreboards.disableAllForNonOps(src.getServer());
            src.sendMessage(Text.literal("已禁止普通玩家开启侧边计分板，已开启的非 OP 玩家计分板已关闭")
                    .formatted(Formatting.GREEN));
        }
        return 1;
    }

    // ---------- scoreboard ----------

    private static int scoreboard(ServerCommandSource src, boolean on) {
        if (!(src.getEntity() instanceof ServerPlayerEntity player)) {
            src.sendMessage(Text.literal("仅玩家可用此指令").formatted(Formatting.RED));
            return 0;
        }
        if (!SidebarScoreboards.setEnabled(player, on)) {
            src.sendMessage(Text.literal("管理员已禁止普通玩家开启侧边计分板").formatted(Formatting.RED));
            return 0;
        }
        src.sendMessage(Text.literal(on ? "已开启侧边计分板" : "已关闭侧边计分板").formatted(Formatting.GREEN));
        return 1;
    }

    // ---------- help ----------

    /** 指令帮助：公共指令人人可见，OP 追加显示管理指令 */
    private static int help(ServerCommandSource src) {
        src.sendMessage(Text.literal("—— 排行榜指令帮助 ——").formatted(Formatting.GOLD));
        src.sendMessage(Text.literal("/leaderboard - 打开排行榜界面（控制台输出文字版）")
                .formatted(Formatting.GRAY));
        src.sendMessage(Text.literal("/leaderboard scoreboard on|off - 开启/关闭个人侧边计分板")
                .formatted(Formatting.GRAY));
        src.sendMessage(Text.literal("/leaderboard help - 显示本帮助")
                .formatted(Formatting.GRAY));
        if (src.getPermissions().hasPermission(new Permission.Level(PermissionLevel.fromLevel(2)))) {
            src.sendMessage(Text.literal("以下仅 OP 可用：").formatted(Formatting.GOLD));
            src.sendMessage(Text.literal("/leaderboard refresh - 手动刷新排行榜")
                    .formatted(Formatting.GRAY));
            src.sendMessage(Text.literal("/leaderboard refresh interval <数字>[t|s|m|h] - 设置自动刷新间隔")
                    .formatted(Formatting.GRAY));
            src.sendMessage(Text.literal("/leaderboard refresh broadcast true|false - 开关自动刷新广播")
                    .formatted(Formatting.GRAY));
            src.sendMessage(Text.literal("/leaderboard mode <compact|normal|full|custom> - 切换显示模式")
                    .formatted(Formatting.GRAY));
            src.sendMessage(Text.literal("/leaderboard player list [all] - 查看玩家名单")
                    .formatted(Formatting.GRAY));
            src.sendMessage(Text.literal("/leaderboard player whitelist add/remove <玩家名> - 管理白名单")
                    .formatted(Formatting.GRAY));
            src.sendMessage(Text.literal("/leaderboard player blacklist add/remove <玩家名> - 管理黑名单")
                    .formatted(Formatting.GRAY));
            src.sendMessage(Text.literal("/leaderboard screen prefix/suffix <特征> - 添加名称筛除特征")
                    .formatted(Formatting.GRAY));
            src.sendMessage(Text.literal("/leaderboard screen remove <特征> - 移除名称筛除特征")
                    .formatted(Formatting.GRAY));
            src.sendMessage(Text.literal("/leaderboard screen list - 查看名称筛除特征")
                    .formatted(Formatting.GRAY));
            src.sendMessage(Text.literal("/leaderboard allowscoreboard true|false - 是否允许普通玩家开启侧边计分板")
                    .formatted(Formatting.GRAY));
        }
        return 1;
    }
}
