package com.ethan.leaderboard;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server Leaderboard 入口。
 * 服务器启动 600 tick（30 秒）后首次生成，之后每 72000 tick（1 小时）生成一次。
 */
public class ServerLeaderboardMod implements DedicatedServerModInitializer {
    public static final String MOD_ID = "server-leaderboard";
    public static final Logger LOGGER = LoggerFactory.getLogger("server-leaderboard");

    private static final long FIRST_DELAY_TICKS = 600L;
    private static final long INTERVAL_TICKS = 72000L;

    private long tickCounter = 0;
    private long nextRunAt = FIRST_DELAY_TICKS;

    @Override
    public void onInitializeServer() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            tickCounter = 0;
            nextRunAt = FIRST_DELAY_TICKS;
            LOGGER.info("[排行榜] 模组已启用，{} 秒后进行首次统计，之后每 {} 分钟刷新一次",
                    FIRST_DELAY_TICKS / 20, INTERVAL_TICKS / 20 / 60);
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;
            if (tickCounter >= nextRunAt) {
                nextRunAt = tickCounter + INTERVAL_TICKS;
                runAndBroadcast(server);
            }
        });

        LeaderboardCommands.register();
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
