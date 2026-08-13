package com.ethan.leaderboard;

import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 排行榜箱子界面（9x6，54 格）。
 * 所有点击均被拦截（不调用 super.onSlotClick，quickMove 返回 EMPTY）。
 *
 * 底行导航（45-53）：
 * 45 排行榜 / 46 通用 / 47 物品 / 48 生物 / 49 食物与饮品
 * 50 返回（仅明细视图，光灵箭）/ 51 上一页 / 52 下一页 / 53 关闭（屏障，始终显示）
 * 精简模式下只有 45 和 53。
 */
public class LeaderboardScreenHandler extends GenericContainerScreenHandler {
    private static final int CONTENT_SLOTS = 45;
    private static final int SLOT_LEADERBOARD = 45;
    private static final int SLOT_KINGS = 46;
    private static final int SLOT_ITEMS = 47;
    private static final int SLOT_MOBS = 48;
    private static final int SLOT_FOOD = 49;
    private static final int SLOT_BACK = 50;
    private static final int SLOT_PREV = 51;
    private static final int SLOT_NEXT = 52;
    private static final int SLOT_CLOSE = 53;
    /** 分组列表视图下图标的起始槽位（第一行最左上角，左对齐） */
    private static final int GROUP_SLOT_BASE = 0;
    /** 普通模式下每个子分类明细最多展示的条目数 */
    private static final int NORMAL_MODE_DETAIL_LIMIT = 36;

    /** 物品组 / 食物与饮品组的 6 个子分类 */
    private static final List<String> ITEM_CATEGORIES = List.of(
            "minecraft:mined", "minecraft:crafted", "minecraft:used",
            "minecraft:broken", "minecraft:picked_up", "minecraft:dropped");
    /** 生物组的 2 个子分类 */
    private static final List<String> MOB_CATEGORIES = List.of("minecraft:killed", "minecraft:killed_by");

    private enum View {
        LEADERBOARD, KINGS, GROUP_LIST, GROUP_DETAIL
    }

    private enum Group {
        ITEMS, MOBS, FOOD
    }

    private final SimpleInventory inventory;
    private final LeaderboardData data;
    private final String mode;
    private final boolean compact;
    private View view = View.LEADERBOARD;
    private Group group = Group.ITEMS;
    private int page = 0;
    private int detailCategory = -1;

    /** 过滤/截断后的当前内容（供分页计算） */
    private List<LeaderboardData.CustomLeader> shownKings = List.of();
    private List<LeaderboardData.ItemRow> shownDetail = List.of();

    public LeaderboardScreenHandler(int syncId, PlayerInventory playerInventory,
                                    SimpleInventory inventory, LeaderboardData data) {
        super(ScreenHandlerType.GENERIC_9X6, syncId, playerInventory, inventory, 6);
        this.inventory = inventory;
        this.data = data;
        this.mode = LeaderboardConfig.get().displayMode;
        this.compact = LeaderboardConfig.MODE_COMPACT.equals(this.mode);
    }

    // ---------- 点击拦截 ----------

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
        // 不调用 super：所有格子不可移动
        if (view == View.GROUP_LIST && slotIndex >= GROUP_SLOT_BASE
                && slotIndex < GROUP_SLOT_BASE + groupCategories().size()) {
            detailCategory = slotIndex - GROUP_SLOT_BASE;
            switchView(View.GROUP_DETAIL);
            return;
        }
        switch (slotIndex) {
            case SLOT_LEADERBOARD -> switchView(View.LEADERBOARD);
            case SLOT_KINGS -> {
                if (!compact) {
                    switchView(View.KINGS);
                }
            }
            case SLOT_ITEMS -> {
                if (!compact) {
                    openGroup(Group.ITEMS);
                }
            }
            case SLOT_MOBS -> {
                if (!compact) {
                    openGroup(Group.MOBS);
                }
            }
            case SLOT_FOOD -> {
                if (!compact) {
                    openGroup(Group.FOOD);
                }
            }
            case SLOT_BACK -> {
                if (view == View.GROUP_DETAIL) {
                    switchView(View.GROUP_LIST);
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
            case SLOT_CLOSE -> {
                if (player instanceof ServerPlayerEntity serverPlayer) {
                    serverPlayer.closeHandledScreen();
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

    private void openGroup(Group target) {
        group = target;
        detailCategory = -1;
        switchView(View.GROUP_LIST);
    }

    public void refresh() {
        for (int i = 0; i < 54; i++) {
            inventory.setStack(i, ItemStack.EMPTY);
        }
        prepareContent();
        if (compact) {
            renderLeaderboard(true);
        } else {
            switch (view) {
                case LEADERBOARD -> renderLeaderboard(false);
                case KINGS -> renderKings();
                case GROUP_LIST -> renderGroupList();
                case GROUP_DETAIL -> renderGroupDetail();
            }
        }
        renderNav();
        inventory.markDirty();
    }

    /** 计算过滤（custom 模式/custom_display.txt、食物剔除）与截断（normal 模式 36 项）后的内容 */
    private void prepareContent() {
        shownKings = List.of();
        shownDetail = List.of();
        if (view == View.KINGS) {
            List<LeaderboardData.CustomLeader> list = new ArrayList<>();
            for (LeaderboardData.CustomLeader leader : data.leadersCustom) {
                if (isEntryShown(leader.stat())) {
                    list.add(leader);
                }
            }
            shownKings = list;
        } else if (view == View.GROUP_DETAIL) {
            List<LeaderboardData.ItemRow> rows = currentCategoryRows();
            List<LeaderboardData.ItemRow> list = new ArrayList<>();
            for (LeaderboardData.ItemRow row : rows) {
                if (!isEntryShown(row.stat())) {
                    continue;
                }
                boolean food = isFoodStat(row.stat());
                if (group == Group.ITEMS && food) {
                    continue;
                }
                if (group == Group.FOOD && !food) {
                    continue;
                }
                list.add(row);
            }
            if (LeaderboardConfig.MODE_NORMAL.equals(mode)
                    && list.size() > NORMAL_MODE_DETAIL_LIMIT) {
                list = new ArrayList<>(list.subList(0, NORMAL_MODE_DETAIL_LIMIT));
            }
            shownDetail = list;
        }
    }

    /** custom 模式按 custom_display.txt 判定，其余模式全部显示 */
    private boolean isEntryShown(String statId) {
        if (LeaderboardConfig.MODE_CUSTOM.equals(mode)) {
            return CustomDisplay.isShown(statId);
        }
        return true;
    }

    /** 该统计项是否属于食物与饮品（按物品组件判定；解析不出物品则不算） */
    private boolean isFoodStat(String statId) {
        Optional<Item> item = Registries.ITEM.getOptionalValue(Identifier.of(statId));
        return item.filter(i -> i != Items.AIR).map(LeaderboardGuiItems::isFoodOrDrink).orElse(false);
    }

    private List<String> groupCategories() {
        return group == Group.MOBS ? MOB_CATEGORIES : ITEM_CATEGORIES;
    }

    private List<LeaderboardData.ItemRow> currentCategoryRows() {
        if (detailCategory < 0 || detailCategory >= groupCategories().size()) {
            return List.of();
        }
        List<LeaderboardData.ItemRow> rows = data.leadersItems.get(groupCategories().get(detailCategory));
        return rows == null ? List.of() : rows;
    }

    private int totalItems() {
        return switch (view) {
            case LEADERBOARD -> data.overall.size();
            case KINGS -> shownKings.size();
            case GROUP_DETAIL -> shownDetail.size();
            case GROUP_LIST -> 0;
        };
    }

    private int pageCount() {
        if (compact) {
            return Math.max(1, (data.overall.size() + CONTENT_SLOTS - 1) / CONTENT_SLOTS);
        }
        return Math.max(1, (totalItems() + CONTENT_SLOTS - 1) / CONTENT_SLOTS);
    }

    /** 排行榜视图：玩家头颅分页（compact 时 lore 只有 9 项数据） */
    private void renderLeaderboard(boolean compactLore) {
        int start = page * CONTENT_SLOTS;
        for (int i = 0; i < CONTENT_SLOTS && start + i < data.overall.size(); i++) {
            int index = start + i;
            String name = data.overall.get(index);
            inventory.setStack(i, buildLeaderboardHead(name, index + 1, compactLore));
        }
    }

    /** 通用榜：每项 custom 统计一格 */
    private void renderKings() {
        int start = page * CONTENT_SLOTS;
        for (int i = 0; i < CONTENT_SLOTS && start + i < shownKings.size(); i++) {
            inventory.setStack(i, buildKingItem(shownKings.get(start + i)));
        }
    }

    /** 分组列表：物品 6 类 / 生物 2 类 / 食物与饮品 6 类 */
    private void renderGroupList() {
        List<String> cats = groupCategories();
        for (int i = 0; i < cats.size(); i++) {
            String category = cats.get(i);
            ItemStack stack = new ItemStack(LeaderboardGuiItems.iconForCategory(category));
            stack.set(DataComponentTypes.CUSTOM_NAME,
                    LeaderboardGuiItems.styled(StatFormat.CATEGORY_NAMES.get(category), LeaderboardGuiItems.COLOR_GOLD));
            stack.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                    LeaderboardGuiItems.styled("点击查看该分类明细", LeaderboardGuiItems.COLOR_GRAY))));
            inventory.setStack(GROUP_SLOT_BASE + i, stack);
        }
    }

    /** 分组明细：物品/食物用对应物品方块图标，生物用刷怪蛋 */
    private void renderGroupDetail() {
        String category = groupCategories().get(detailCategory);
        int start = page * CONTENT_SLOTS;
        for (int i = 0; i < CONTENT_SLOTS && start + i < shownDetail.size(); i++) {
            inventory.setStack(i, buildDetailItem(shownDetail.get(start + i), category));
        }
    }

    /** 底行导航栏 */
    private void renderNav() {
        inventory.setStack(SLOT_LEADERBOARD, navItem(new ItemStack(Items.CLOCK), "排行榜",
                compact || view == View.LEADERBOARD));
        if (!compact) {
            inventory.setStack(SLOT_KINGS, navItem(new ItemStack(Items.NETHER_STAR), "通用", view == View.KINGS));
            inventory.setStack(SLOT_ITEMS, navItem(new ItemStack(Items.CHEST), "物品",
                    group == Group.ITEMS && isGroupView()));
            inventory.setStack(SLOT_MOBS, navItem(new ItemStack(Items.SKELETON_SKULL), "生物",
                    group == Group.MOBS && isGroupView()));
            inventory.setStack(SLOT_FOOD, navItem(new ItemStack(Items.APPLE), "食物与饮品",
                    group == Group.FOOD && isGroupView()));
            if (view == View.GROUP_DETAIL) {
                inventory.setStack(SLOT_BACK, navItem(new ItemStack(Items.SPECTRAL_ARROW), "返回分类列表", false));
            }
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
        inventory.setStack(SLOT_CLOSE, navItem(new ItemStack(Items.BARRIER), "关闭", false));
    }

    private boolean isGroupView() {
        return view == View.GROUP_LIST || view == View.GROUP_DETAIL;
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

    private ItemStack buildLeaderboardHead(String name, int rank, boolean compactLore) {
        ItemStack stack = playerHead(name);
        stack.set(DataComponentTypes.CUSTOM_NAME,
                LeaderboardGuiItems.styled("#" + rank + " " + name, LeaderboardGuiItems.rankColor(rank)));
        LeaderboardData.PlayerSummary ps = data.playerSummary.get(name);
        List<net.minecraft.text.Text> lore = new ArrayList<>();
        if (!compactLore) {
            lore.add(LeaderboardGuiItems.styled("综合分：" + data.score.get(name), LeaderboardGuiItems.COLOR_GREEN));
            lore.add(LeaderboardGuiItems.styled("冠军项数：" + data.titles.get(name), LeaderboardGuiItems.COLOR_GREEN));
        }
        lore.add(LeaderboardGuiItems.styled("游戏时间："
                + StatFormat.formatValue("minecraft:play_time", ps.playTime()), LeaderboardGuiItems.COLOR_GRAY));
        lore.add(LeaderboardGuiItems.styled("死亡次数：" + ps.deaths(), LeaderboardGuiItems.COLOR_GRAY));
        lore.add(LeaderboardGuiItems.styled("击杀生物：" + StatFormat.num(ps.mobKills()), LeaderboardGuiItems.COLOR_GRAY));
        lore.add(LeaderboardGuiItems.styled("造成伤害："
                + StatFormat.formatValue("minecraft:damage_dealt", ps.damageDealt()), LeaderboardGuiItems.COLOR_GRAY));
        lore.add(LeaderboardGuiItems.styled("承受伤害："
                + StatFormat.formatValue("minecraft:damage_taken", ps.damageTaken()), LeaderboardGuiItems.COLOR_GRAY));
        lore.add(LeaderboardGuiItems.styled("移动距离："
                + StatFormat.formatValue("minecraft:walk_one_cm", ps.distance()), LeaderboardGuiItems.COLOR_GRAY));
        lore.add(LeaderboardGuiItems.styled("挖掘：" + StatFormat.num(ps.minedTotal()), LeaderboardGuiItems.COLOR_GRAY));
        lore.add(LeaderboardGuiItems.styled("合成：" + StatFormat.num(ps.craftedTotal()), LeaderboardGuiItems.COLOR_GRAY));
        lore.add(LeaderboardGuiItems.styled("鞘翅飞行："
                + StatFormat.formatValue("minecraft:aviate_one_cm", ps.aviate()), LeaderboardGuiItems.COLOR_GRAY));
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
        boolean mobGroup = group == Group.MOBS;
        ItemStack stack = new ItemStack(mobGroup
                ? LeaderboardGuiItems.spawnEggFor(row.stat())
                : LeaderboardGuiItems.resolveStatItem(row.stat(), category));
        String displayName = mobGroup ? Lang.entityName(row.stat()) : Lang.itemName(row.stat());
        stack.set(DataComponentTypes.CUSTOM_NAME,
                LeaderboardGuiItems.styled(displayName, LeaderboardGuiItems.COLOR_WHITE));
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
