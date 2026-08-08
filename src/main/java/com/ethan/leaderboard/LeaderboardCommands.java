package com.ethan.leaderboard;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import static net.minecraft.server.command.CommandManager.literal;

/**
 * /leaderboard 指令：
 * /leaderboard 玩家 -> 打开箱子 GUI；控制台/RCON -> 聊天文字版
 * /leaderboard chat 玩家强制看聊天文字版
 * /leaderboard kings 数据之王 / /leaderboard refresh 立即刷新（权限 2）
 */
public final class LeaderboardCommands {

    private LeaderboardCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(literal("leaderboard")
                        .executes(ctx -> openLeaderboard(ctx.getSource()))
                        .then(literal("chat")
                                .executes(ctx -> showOverall(ctx.getSource())))
                        .then(literal("kings")
                                .executes(ctx -> showKings(ctx.getSource())))
                        .then(literal("refresh")
                                .requires(src -> src.hasPermissionLevel(2))
                                .executes(ctx -> refresh(ctx.getSource())))));
    }

    /** 打开 GUI；无数据时先生成一次；非玩家来源回退到聊天文字版 */
    private static int openLeaderboard(ServerCommandSource src) {
        LeaderboardData data = LeaderboardGenerator.getLastData();
        if (data == null) {
            LeaderboardGenerator.generate(src.getServer());
            data = LeaderboardGenerator.getLastData();
        }
        if (data == null || data.overall.isEmpty()) {
            src.sendMessage(Text.literal("暂无排行榜数据，请稍后再试").formatted(Formatting.RED));
            return 0;
        }
        if (src.getEntity() instanceof ServerPlayerEntity player) {
            LeaderboardGui.open(player, data);
            return 1;
        }
        return showOverall(src);
    }

    private static int showOverall(ServerCommandSource src) {
        LeaderboardData data = LeaderboardGenerator.getLastData();
        if (data == null || data.overall.isEmpty()) {
            src.sendMessage(Text.literal("暂无排行榜数据，请稍后再试").formatted(Formatting.RED));
            return 0;
        }
        src.sendMessage(Text.literal("—— 综合排行榜 TOP 10 ——").formatted(Formatting.GOLD));
        int limit = Math.min(10, data.overall.size());
        for (int i = 0; i < limit; i++) {
            String name = data.overall.get(i);
            int rank = i + 1;
            Text line = Text.literal("#" + rank + " " + name)
                    .formatted(rank <= 3 ? Formatting.GOLD : Formatting.GREEN)
                    .append(Text.literal(" - " + data.score.get(name) + " 分，冠军 " + data.titles.get(name) + " 项")
                            .formatted(Formatting.GREEN));
            src.sendMessage(line);
        }
        return 1;
    }

    private static int showKings(ServerCommandSource src) {
        LeaderboardData data = LeaderboardGenerator.getLastData();
        if (data == null || data.leadersCustom.isEmpty()) {
            src.sendMessage(Text.literal("暂无排行榜数据，请稍后再试").formatted(Formatting.RED));
            return 0;
        }
        src.sendMessage(Text.literal("—— 数据之王 ——").formatted(Formatting.GOLD));
        for (LeaderboardData.CustomLeader leader : data.leadersCustom) {
            LeaderboardData.Entry top = leader.ranking().get(0);
            src.sendMessage(Text.literal(StatFormat.statDisplayName(leader.stat()) + "：")
                    .formatted(Formatting.GREEN)
                    .append(Text.literal(top.name()).formatted(Formatting.GOLD))
                    .append(Text.literal(" " + StatFormat.formatValue(leader.stat(), top.value()))
                            .formatted(Formatting.GREEN)));
        }
        return 1;
    }

    private static int refresh(ServerCommandSource src) {
        boolean ok = LeaderboardGenerator.generate(src.getServer());
        if (ok) {
            LeaderboardData data = LeaderboardGenerator.getLastData();
            String top = data.overall.get(0);
            src.sendFeedback(() -> Text.literal("排行榜已重新生成，综合第一：" + top
                    + "（" + data.score.get(top) + " 分）").formatted(Formatting.GREEN), true);
            return 1;
        }
        src.sendError(Text.literal("排行榜生成失败，请查看服务端日志"));
        return 0;
    }
}
