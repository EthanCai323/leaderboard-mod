package com.ethan.leaderboard;

import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 排行榜箱子界面（9x6，54 格）。
 * 所有点击均被拦截（不调用 super.onSlotClick，quickMove 返回 EMPTY），
 * 玩家无法拿走或移动任何物品。
 *
 * 底行（45-53）为导航栏：
 * 45 综合排行榜 / 46 数据之王 / 47 物品分类榜 / 48 玩家总览 / 49 关闭
 * 50 返回（仅分类明细视图）/ 52 上一页 / 53 下一页
 */
public class LeaderboardScreenHandler extends GenericContainerScreenHandler {
    private static final int CONTENT_SLOTS = 45;
    private static final int SLOT_OVERALL = 45;
    private static final int SLOT_KINGS = 46;
    private static final int SLOT_CATEGORIES = 47;
    private static final int SLOT_SUMMARY = 48;
    private static final int SLOT_CLOSE = 49;
    private static final int SLOT_BACK = 50;
    private static final int SLOT_PREV = 52;
    private static final int SLOT_NEXT = 53;
    /** 分类视图下 8 个分类图标的起始槽位（第二行居中） */
    private static final int CATEGORY_SLOT_BASE = 10;

    private static final List<String> CATEGORIES = List.copyOf(StatFormat.CATEGORY_NAMES.keySet());

    private enum View {
        OVERALL, KINGS, CATEGORIES, CATEGORY_DETAIL, SUMMARY
    }

    private final SimpleInventory inventory;
    private final LeaderboardData data;
    private View view = View.OVERALL;
    private int page = 0;
    private int detailCategory = -1;

    public LeaderboardScreenHandler(int syncId, PlayerInventory playerInventory,
                                    SimpleInventory inventory, LeaderboardData data) {
        super(ScreenHandlerType.GENERIC_9X6, syncId, playerInventory, inventory, 6);
        this.inventory = inventory;
        this.data = data;
    }

    // ---------- 点击拦截 ----------

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
        // 不调用 super：所有格子不可移动
        if (view == View.CATEGORIES && slotIndex >= CATEGORY_SLOT_BASE
                && slotIndex < CATEGORY_SLOT_BASE + CATEGORIES.size()) {
            detailCategory = slotIndex - CATEGORY_SLOT_BASE;
            switchView(View.CATEGORY_DETAIL);
            return;
        }
        switch (slotIndex) {
            case SLOT_OVERALL -> switchView(View.OVERALL);
            case SLOT_KINGS -> switchView(View.KINGS);
            case SLOT_CATEGORIES -> switchView(View.CATEGORIES);
            case SLOT_SUMMARY -> switchView(View.SUMMARY);
            case SLOT_CLOSE -> {
                if (player instanceof ServerPlayerEntity serverPlayer) {
                    serverPlayer.closeHandledScreen();
                }
            }
            case SLOT_BACK -> {
                if (view == View.CATEGORY_DETAIL) {
                    switchView(View.CATEGORIES);
                }
            }
            case SLOT_PREV -> {
                if (page > 0) {
                    page--;
                    refresh();
                }
            }
            case SLOT_NEXT -> {
                if (page < pageCount() - 1) {
                    page++;
                    refresh();
                }
            }
            default -> {
                // 内容区及其他格子：忽略
            }
        }
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slot) {
        return ItemStack.EMPTY;
    }

    // ---------- 视图渲染 ----------

    private void switchView(View target) {
        view = target;
        page = 0;
        refresh();
    }

    public void refresh() {
        for (int i = 0; i < 54; i++) {
            inventory.setStack(i, ItemStack.EMPTY);
        }
        switch (view) {
            case OVERALL -> renderPlayers(false);
            case SUMMARY -> renderPlayers(true);
            case KINGS -> renderKings();
            case CATEGORIES -> renderCategories();
            case CATEGORY_DETAIL -> renderCategoryDetail();
        }
        renderNav();
        inventory.markDirty();
    }

    private int totalItems() {
        return switch (view) {
            case OVERALL, SUMMARY -> data.overall.size();
            case KINGS -> data.leadersCustom.size();
            case CATEGORIES -> 0;
            case CATEGORY_DETAIL -> {
                List<LeaderboardData.ItemRow> rows = currentCategoryRows();
                yield rows == null ? 0 : rows.size();
            }
        };
    }

    private int pageCount() {
        return Math.max(1, (totalItems() + CONTENT_SLOTS - 1) / CONTENT_SLOTS);
    }

    private List<LeaderboardData.ItemRow> currentCategoryRows() {
        if (detailCategory < 0 || detailCategory >= CATEGORIES.size()) {
            return List.of();
        }
        List<LeaderboardData.ItemRow> rows = data.leadersItems.get(CATEGORIES.get(detailCategory));
        return rows == null ? List.of() : rows;
    }

    /** 综合排行榜 / 玩家总览：玩家头颅分页 */
    private void renderPlayers(boolean summary) {
        int start = page * CONTENT_SLOTS;
        for (int i = 0; i < CONTENT_SLOTS && start + i < data.overall.size(); i++) {
            int index = start + i;
            String name = data.overall.get(index);
            inventory.setStack(i, summary ? buildSummaryHead(name) : buildOverallHead(name, index + 1));
        }
    }

    /** 数据之王：每项 custom 统计一格 */
    private void renderKings() {
        int start = page * CONTENT_SLOTS;
        for (int i = 0; i < CONTENT_SLOTS && start + i < data.leadersCustom.size(); i++) {
            inventory.setStack(i, buildKingItem(data.leadersCustom.get(start + i)));
        }
    }

    /** 物品分类榜：8 个分类图标 */
    private void renderCategories() {
        for (int i = 0; i < CATEGORIES.size(); i++) {
            String category = CATEGORIES.get(i);
            ItemStack stack = new ItemStack(LeaderboardGuiItems.iconForCategory(category));
            stack.set(DataComponentTypes.CUSTOM_NAME,
                    LeaderboardGuiItems.styled(StatFormat.CATEGORY_NAMES.get(category), LeaderboardGuiItems.COLOR_GOLD));
            stack.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                    LeaderboardGuiItems.styled("点击查看该分类明细", LeaderboardGuiItems.COLOR_GRAY))));
            inventory.setStack(CATEGORY_SLOT_BASE + i, stack);
        }
    }

    /** 分类明细：直接用统计项对应的物品/方块做图标 */
    private void renderCategoryDetail() {
        String category = CATEGORIES.get(detailCategory);
        List<LeaderboardData.ItemRow> rows = currentCategoryRows();
        int start = page * CONTENT_SLOTS;
        for (int i = 0; i < CONTENT_SLOTS && start + i < rows.size(); i++) {
            inventory.setStack(i, buildDetailItem(rows.get(start + i), category));
        }
    }

    /** 底行导航栏 */
    private void renderNav() {
        inventory.setStack(SLOT_OVERALL, navItem(new ItemStack(Items.CLOCK), "综合排行榜", view == View.OVERALL));
        inventory.setStack(SLOT_KINGS, navItem(new ItemStack(Items.NETHER_STAR), "数据之王", view == View.KINGS));
        inventory.setStack(SLOT_CATEGORIES, navItem(new ItemStack(Items.CHEST), "物品分类榜",
                view == View.CATEGORIES || view == View.CATEGORY_DETAIL));
        inventory.setStack(SLOT_SUMMARY, navItem(new ItemStack(Items.PLAYER_HEAD), "玩家总览", view == View.SUMMARY));
        inventory.setStack(SLOT_CLOSE, navItem(new ItemStack(Items.BARRIER), "关闭", false));
        if (view == View.CATEGORY_DETAIL) {
            inventory.setStack(SLOT_BACK, navItem(new ItemStack(Items.ARROW), "返回物品分类", false));
        }
        if (pageCount() > 1) {
            if (page > 0) {
                inventory.setStack(SLOT_PREV, navItem(new ItemStack(Items.ARROW),
                        "上一页（第 " + page + "/" + pageCount() + " 页）", false));
            }
            if (page < pageCount() - 1) {
                inventory.setStack(SLOT_NEXT, navItem(new ItemStack(Items.ARROW),
                        "下一页（第 " + (page + 2) + "/" + pageCount() + " 页）", false));
            }
        }
    }

    private ItemStack navItem(ItemStack stack, String name, boolean active) {
        stack.set(DataComponentTypes.CUSTOM_NAME, LeaderboardGuiItems.styled(name,
                active ? LeaderboardGuiItems.COLOR_GREEN : LeaderboardGuiItems.COLOR_WHITE));
        if (active) {
            stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        return stack;
    }

    // ---------- 物品构建 ----------

    private ItemStack playerHead(String name) {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        stack.set(DataComponentTypes.PROFILE,
                new ProfileComponent(Optional.of(name), Optional.empty(), new PropertyMap()));
        return stack;
    }

    private ItemStack buildOverallHead(String name, int rank) {
        ItemStack stack = playerHead(name);
        stack.set(DataComponentTypes.CUSTOM_NAME,
                LeaderboardGuiItems.styled("#" + rank + " " + name, LeaderboardGuiItems.rankColor(rank)));
        LeaderboardData.PlayerSummary ps = data.playerSummary.get(name);
        List<net.minecraft.text.Text> lore = new ArrayList<>();
        lore.add(LeaderboardGuiItems.styled("综合分：" + data.score.get(name), LeaderboardGuiItems.COLOR_GREEN));
        lore.add(LeaderboardGuiItems.styled("冠军项数：" + data.titles.get(name), LeaderboardGuiItems.COLOR_GREEN));
        lore.add(LeaderboardGuiItems.styled("游戏时间："
                + StatFormat.formatValue("minecraft:play_time", ps.playTime()), LeaderboardGuiItems.COLOR_GRAY));
        lore.add(LeaderboardGuiItems.styled("死亡：" + ps.deaths(), LeaderboardGuiItems.COLOR_GRAY));
        lore.add(LeaderboardGuiItems.styled("击杀生物：" + StatFormat.num(ps.mobKills()), LeaderboardGuiItems.COLOR_GRAY));
        lore.add(LeaderboardGuiItems.styled("总挖掘：" + StatFormat.num(ps.minedTotal()), LeaderboardGuiItems.COLOR_GRAY));
        stack.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return stack;
    }

    private ItemStack buildSummaryHead(String name) {
        ItemStack stack = playerHead(name);
        stack.set(DataComponentTypes.CUSTOM_NAME,
                LeaderboardGuiItems.styled(name, LeaderboardGuiItems.COLOR_GOLD));
        LeaderboardData.PlayerSummary ps = data.playerSummary.get(name);
        List<net.minecraft.text.Text> lore = new ArrayList<>();
        lore.add(LeaderboardGuiItems.styled("游戏时间："
                + StatFormat.formatValue("minecraft:play_time", ps.playTime()), LeaderboardGuiItems.COLOR_GRAY));
        lore.add(LeaderboardGuiItems.styled("死亡：" + ps.deaths(), LeaderboardGuiItems.COLOR_GRAY));
        lore.add(LeaderboardGuiItems.styled("击杀生物：" + StatFormat.num(ps.mobKills()), LeaderboardGuiItems.COLOR_GRAY));
        lore.add(LeaderboardGuiItems.styled("造成伤害："
                + StatFormat.formatValue("minecraft:damage_dealt", ps.damageDealt()), LeaderboardGuiItems.COLOR_GRAY));
        lore.add(LeaderboardGuiItems.styled("承受伤害："
                + StatFormat.formatValue("minecraft:damage_taken", ps.damageTaken()), LeaderboardGuiItems.COLOR_GRAY));
        lore.add(LeaderboardGuiItems.styled("总移动距离："
                + StatFormat.formatValue("minecraft:walk_one_cm", ps.distance()), LeaderboardGuiItems.COLOR_GRAY));
        lore.add(LeaderboardGuiItems.styled("总挖掘：" + StatFormat.num(ps.minedTotal()), LeaderboardGuiItems.COLOR_GRAY));
        lore.add(LeaderboardGuiItems.styled("总合成：" + StatFormat.num(ps.craftedTotal()), LeaderboardGuiItems.COLOR_GRAY));
        stack.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return stack;
    }

    private ItemStack buildKingItem(LeaderboardData.CustomLeader leader) {
        String stat = leader.stat();
        ItemStack stack = new ItemStack(LeaderboardGuiItems.iconForCustomStat(stat));
        stack.set(DataComponentTypes.CUSTOM_NAME,
                LeaderboardGuiItems.styled(StatFormat.statDisplayName(stat), LeaderboardGuiItems.COLOR_GOLD));
        List<LeaderboardData.Entry> ranking = leader.ranking();
        List<net.minecraft.text.Text> lore = new ArrayList<>();
        lore.add(placeLine("第一名", ranking, 0, stat, LeaderboardGuiItems.COLOR_GREEN));
        lore.add(placeLine("第二名", ranking, 1, stat, LeaderboardGuiItems.COLOR_GRAY));
        stack.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return stack;
    }

    private ItemStack buildDetailItem(LeaderboardData.ItemRow row, String category) {
        ItemStack stack = new ItemStack(LeaderboardGuiItems.resolveStatItem(row.stat(), category));
        stack.set(DataComponentTypes.CUSTOM_NAME,
                LeaderboardGuiItems.styled(StatFormat.prettifyId(row.stat()), LeaderboardGuiItems.COLOR_WHITE));
        List<LeaderboardData.Entry> ranking = row.ranking();
        List<net.minecraft.text.Text> lore = new ArrayList<>();
        lore.add(placeLine("第一名", ranking, 0, row.stat(), LeaderboardGuiItems.COLOR_GREEN));
        lore.add(placeLine("第二名", ranking, 1, row.stat(), LeaderboardGuiItems.COLOR_GRAY));
        lore.add(LeaderboardGuiItems.styled("全员总量：" + StatFormat.num(row.total()), LeaderboardGuiItems.COLOR_GRAY));
        stack.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return stack;
    }

    private net.minecraft.text.Text placeLine(String label, List<LeaderboardData.Entry> ranking,
                                              int index, String stat, int color) {
        if (ranking.size() <= index) {
            return LeaderboardGuiItems.styled(label + "：暂无", color);
        }
        LeaderboardData.Entry entry = ranking.get(index);
        return LeaderboardGuiItems.styled(label + "：" + entry.name()
                + "（" + StatFormat.formatValue(stat, entry.value()) + "）", color);
    }
}
