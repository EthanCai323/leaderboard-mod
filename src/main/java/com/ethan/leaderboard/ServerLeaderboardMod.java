package com.ethan.leaderboard;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server Leaderboard 入口。
 * 默认启动 600 tick（30 秒）后首次生成，之后按 leaderboard/config.json 的
 * refreshIntervalTicks 间隔生成（默认 72000 tick / 1 小时，0 表示关闭自动刷新）。
 * 侧边计分板每 20 tick 刷新一次。
 */
public class ServerLeaderboardMod implements DedicatedServerModInitializer {
    public static final String MOD_ID = "server-leaderboard";
    public static final Logger LOGGER = LoggerFactory.getLogger("server-leaderboard");

    private static final long FIRST_DELAY_TICKS = 600L;

    private static long tickCounter = 0;
    private static long nextRunAt = Long.MAX_VALUE;

    @Override
    public void onInitializeServer() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LeaderboardConfig.load();
            PlayerFilter.load();
            SidebarScoreboards.load();
            CustomDisplay.load();
            Lang.reload();
            tickCounter = 0;
            long interval = LeaderboardConfig.get().refreshIntervalTicks;
            nextRunAt = interval > 0 ? FIRST_DELAY_TICKS : Long.MAX_VALUE;
            if (interval > 0) {
                LOGGER.info("[排行榜] 模组已启用，{} 秒后进行首次统计，之后每 {} tick 刷新一次",
                        FIRST_DELAY_TICKS / 20, interval);
            } else {
                LOGGER.info("[排行榜] 模组已启用，自动刷新已关闭（仅手动 /leaderboard refresh）");
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;
            SidebarScoreboards.tick(server);
            long interval = LeaderboardConfig.get().refreshIntervalTicks;
            if (interval <= 0) {
                return;
            }
            if (tickCounter >= nextRunAt) {
                nextRunAt = tickCounter + interval;
                runAndBroadcast(server);
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                SidebarScoreboards.onJoin(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                SidebarScoreboards.onDisconnect(handler.player));

        LeaderboardCommands.register();
    }

    /** 修改刷新间隔后重排定时任务（热生效） */
    public static void resetSchedule() {
        long interval = LeaderboardConfig.get().refreshIntervalTicks;
        nextRunAt = interval > 0 ? tickCounter + interval : Long.MAX_VALUE;
    }

    /** 定时刷新并广播综合第一 */
    public static void runAndBroadcast(MinecraftServer server) {
        boolean ok = LeaderboardGenerator.generate(server);
        if (!ok) {
            return;
        }
        LeaderboardData data = LeaderboardGenerator.getLastData();
        if (data == null || data.overall.isEmpty()) {
            return;
        }
        String top = data.overall.get(0);
        server.getPlayerManager().broadcast(
                Text.literal("排行榜已更新，综合第一：" + top + "，" + data.score.get(top)
                        + " 分。输入 /leaderboard 查看").formatted(Formatting.GOLD),
                false);
    }
}
