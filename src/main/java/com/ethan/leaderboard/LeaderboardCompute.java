package com.ethan.leaderboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.ToLongFunction;

/**
 * 排行计算，移植自 leaderboard.py 的 compute()。
 * allStats: 玩家名 -> 分类 -> 统计项 -> 数值
 * 综合得分只统计当前显示模式实际展示的分类/统计项：
 * 精简模式只算玩家总览的 9 项核心数据；普通模式算全部通用统计与
 * 物品/生物各分类前 36 项；全部模式算所有项；自定义模式算 custom_display.txt 开启的项。
 */
public final class LeaderboardCompute {

    /** 与普通模式 GUI 明细上限一致（LeaderboardScreenHandler.NORMAL_MODE_DETAIL_LIMIT） */
    private static final int NORMAL_MODE_ITEM_LIMIT = 36;

    private LeaderboardCompute() {
    }

    private static long get(Map<String, Map<String, Map<String, Long>>> allStats,
                            String cat, String stat, String name) {
        Map<String, Map<String, Long>> byCat = allStats.get(name);
        if (byCat == null) {
            return 0;
        }
        Map<String, Long> byStat = byCat.get(cat);
        if (byStat == null) {
            return 0;
        }
        return byStat.getOrDefault(stat, 0L);
    }

    public static LeaderboardData compute(Map<String, Map<String, Map<String, Long>>> allStats) {
        List<String> players = new ArrayList<>(allStats.keySet());

        // --- custom 类逐项排行 ---
        Set<String> customStats = new TreeSet<>();
        for (Map<String, Map<String, Long>> s : allStats.values()) {
            Map<String, Long> custom = s.get("minecraft:custom");
            if (custom != null) {
                customStats.addAll(custom.keySet());
            }
        }
        customStats.removeAll(StatFormat.EXCLUDED_STATS);

        List<LeaderboardData.CustomLeader> leadersCustom = new ArrayList<>();
        for (String stat : customStats) {
            List<LeaderboardData.Entry> ranking = new ArrayList<>();
            for (String n : players) {
                long v = get(allStats, "minecraft:custom", stat, n);
                if (v > 0) {
                    ranking.add(new LeaderboardData.Entry(n, v));
                }
            }
            ranking.sort((a, b) -> Long.compare(b.value(), a.value()));
            if (!ranking.isEmpty()) {
                leadersCustom.add(new LeaderboardData.CustomLeader(stat, ranking));
            }
        }

        // --- 物品类逐项排行 ---
        Map<String, List<LeaderboardData.ItemRow>> leadersItems = new LinkedHashMap<>();
        for (String cat : StatFormat.CATEGORY_NAMES.keySet()) {
            Set<String> statsInCat = new TreeSet<>();
            for (Map<String, Map<String, Long>> s : allStats.values()) {
                Map<String, Long> m = s.get(cat);
                if (m != null) {
                    statsInCat.addAll(m.keySet());
                }
            }
            List<LeaderboardData.ItemRow> rows = new ArrayList<>();
            for (String stat : statsInCat) {
                List<LeaderboardData.Entry> ranking = new ArrayList<>();
                for (String n : players) {
                    long v = get(allStats, cat, stat, n);
                    if (v > 0) {
                        ranking.add(new LeaderboardData.Entry(n, v));
                    }
                }
                ranking.sort((a, b) -> Long.compare(b.value(), a.value()));
                if (!ranking.isEmpty()) {
                    long total = ranking.stream().mapToLong(LeaderboardData.Entry::value).sum();
                    rows.add(new LeaderboardData.ItemRow(stat, ranking, total));
                }
            }
            rows.sort((a, b) -> Long.compare(b.total(), a.total()));
            leadersItems.put(cat, rows);
        }

        // --- 各玩家分类总量（供玩家总览与精简模式评分） ---
        Map<String, Map<String, Long>> categoryTotals = new HashMap<>();
        for (String cat : StatFormat.CATEGORY_NAMES.keySet()) {
            for (String n : players) {
                long total = 0;
                Map<String, Long> m = allStats.getOrDefault(n, Map.of()).get(cat);
                if (m != null) {
                    for (long v : m.values()) {
                        total += v;
                    }
                }
                categoryTotals.computeIfAbsent(n, k -> new HashMap<>()).put(cat, total);
            }
        }

        // --- 玩家关键数据总览 ---
        Map<String, LeaderboardData.PlayerSummary> playerSummary = new HashMap<>();
        for (String n : players) {
            Map<String, Long> c = allStats.getOrDefault(n, Map.of())
                    .getOrDefault("minecraft:custom", Map.of());
            long distance = 0;
            for (Map.Entry<String, Long> e : c.entrySet()) {
                if (e.getKey().endsWith("_one_cm")) {
                    distance += e.getValue();
                }
            }
            playerSummary.put(n, new LeaderboardData.PlayerSummary(
                    c.getOrDefault("minecraft:play_time", 0L),
                    c.getOrDefault("minecraft:deaths", 0L),
                    c.getOrDefault("minecraft:mob_kills", 0L),
                    c.getOrDefault("minecraft:damage_dealt", 0L),
                    c.getOrDefault("minecraft:damage_taken", 0L),
                    distance,
                    categoryTotals.getOrDefault(n, Map.of()).getOrDefault("minecraft:mined", 0L),
                    categoryTotals.getOrDefault(n, Map.of()).getOrDefault("minecraft:crafted", 0L),
                    c.getOrDefault("minecraft:aviate_one_cm", 0L)));
        }

        // --- 综合评分：只统计当前显示模式实际展示的分类/统计项 ---
        Map<String, Integer> score = new HashMap<>();
        Map<String, Integer> titles = new HashMap<>();
        for (String n : players) {
            score.put(n, 0);
            titles.put(n, 0);
        }

        String mode = LeaderboardConfig.get().displayMode;
        if (LeaderboardConfig.MODE_COMPACT.equals(mode)) {
            // 精简模式：只算玩家头颅 lore 中的 9 项核心数据
            awardSummary(players, playerSummary, LeaderboardData.PlayerSummary::playTime, score, titles);
            awardSummary(players, playerSummary, LeaderboardData.PlayerSummary::deaths, score, titles);
            awardSummary(players, playerSummary, LeaderboardData.PlayerSummary::mobKills, score, titles);
            awardSummary(players, playerSummary, LeaderboardData.PlayerSummary::damageDealt, score, titles);
            awardSummary(players, playerSummary, LeaderboardData.PlayerSummary::damageTaken, score, titles);
            awardSummary(players, playerSummary, LeaderboardData.PlayerSummary::distance, score, titles);
            awardSummary(players, playerSummary, LeaderboardData.PlayerSummary::minedTotal, score, titles);
            awardSummary(players, playerSummary, LeaderboardData.PlayerSummary::craftedTotal, score, titles);
            awardSummary(players, playerSummary, LeaderboardData.PlayerSummary::aviate, score, titles);
        } else {
            boolean custom = LeaderboardConfig.MODE_CUSTOM.equals(mode);
            boolean normal = LeaderboardConfig.MODE_NORMAL.equals(mode);
            // 通用榜：自定义模式只算 custom_display.txt 开启的统计项
            for (LeaderboardData.CustomLeader leader : leadersCustom) {
                if (custom && !CustomDisplay.isShown(leader.stat())) {
                    continue;
                }
                award(leader.ranking(), score, titles);
            }
            // 物品/生物榜：普通模式每类只算前 36 项，自定义模式只算开启的项
            for (List<LeaderboardData.ItemRow> rows : leadersItems.values()) {
                int awarded = 0;
                for (LeaderboardData.ItemRow row : rows) {
                    if (custom && !CustomDisplay.isShown(row.stat())) {
                        continue;
                    }
                    if (normal && awarded >= NORMAL_MODE_ITEM_LIMIT) {
                        break;
                    }
                    award(row.ranking(), score, titles);
                    awarded++;
                }
            }
        }

        List<String> overall = new ArrayList<>(players);
        overall.sort((a, b) -> {
            int cmp = Integer.compare(score.get(b), score.get(a));
            if (cmp != 0) {
                return cmp;
            }
            cmp = Integer.compare(titles.get(b), titles.get(a));
            if (cmp != 0) {
                return cmp;
            }
            return a.compareTo(b);
        });

        return new LeaderboardData(leadersCustom, leadersItems, overall, score, titles, playerSummary);
    }

    /** 精简模式评分：按玩家总览中的某一项核心数据排名给分 */
    private static void awardSummary(List<String> players,
                                     Map<String, LeaderboardData.PlayerSummary> summary,
                                     ToLongFunction<LeaderboardData.PlayerSummary> metric,
                                     Map<String, Integer> score, Map<String, Integer> titles) {
        List<LeaderboardData.Entry> ranking = new ArrayList<>();
        for (String n : players) {
            LeaderboardData.PlayerSummary ps = summary.get(n);
            if (ps == null) {
                continue;
            }
            long v = metric.applyAsLong(ps);
            if (v > 0) {
                ranking.add(new LeaderboardData.Entry(n, v));
            }
        }
        ranking.sort((a, b) -> Long.compare(b.value(), a.value()));
        if (!ranking.isEmpty()) {
            award(ranking, score, titles);
        }
    }

    /** 按名次给分：第 1 名 N 分，第 2 名 N-1 分……并列同名次；并列第一都算冠军 */
    private static void award(List<LeaderboardData.Entry> ranking,
                              Map<String, Integer> score, Map<String, Integer> titles) {
        int n = ranking.size();
        long prevVal = 0;
        int prevRank = 0;
        int i = 0;
        for (LeaderboardData.Entry e : ranking) {
            i++;
            int rank = (i == 1 || e.value() != prevVal) ? i : prevRank;
            prevVal = e.value();
            prevRank = rank;
            score.merge(e.name(), n - rank + 1, Integer::sum);
        }
        if (!ranking.isEmpty()) {
            long topVal = ranking.get(0).value();
            for (LeaderboardData.Entry e : ranking) {
                if (e.value() == topVal) {
                    titles.merge(e.name(), 1, Integer::sum);
                }
            }
        }
    }
}
