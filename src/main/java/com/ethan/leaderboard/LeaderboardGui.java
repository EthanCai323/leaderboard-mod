package com.ethan.leaderboard;

import net.minecraft.inventory.SimpleInventory;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * 打开排行榜箱子界面的入口。界面展示的是最近一次生成的数据快照。
 */
public final class LeaderboardGui {

    private LeaderboardGui() {
    }

    public static void open(ServerPlayerEntity player, LeaderboardData data) {
        // 打开时热加载翻译表与自定义显示配置，文件改动即时生效
        Lang.reload();
        CustomDisplay.load();
        SimpleInventory inventory = new SimpleInventory(54);
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, playerInventory, p) -> {
                    LeaderboardScreenHandler handler =
                            new LeaderboardScreenHandler(syncId, playerInventory, inventory, data);
                    handler.refresh();
                    return handler;
                },
                Text.literal("服务器排行榜")));
    }
}
