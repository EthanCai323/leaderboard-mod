package com.ethan.leaderboard;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次排行计算的结果数据。字段封装为私有，外部通过 getter 获取不可变视图。
 */
public class LeaderboardData {
    /** 每项 custom 统计的第一名榜单（按统计 id 排序） */
    private final List<CustomLeader> leadersCustom;
    /** 物品分类榜：分类 -> 前 30 项 */
    private final Map<String, List<ItemRow>> leadersItems;
    /** 综合排名（玩家名，按综合分降序） */
    private final List<String> overall;
    /** 玩家 -> 综合分 */
    private final Map<String, Integer> score;
    /** 玩家 -> 冠军项数 */
    private final Map<String, Integer> titles;
    /** 玩家 -> 关键数据总览 */
    private final Map<String, PlayerSummary> playerSummary;
    /** 玩家名 -> UUID（小写带连字符，与 stats 文件名一致） */
    private final Map<String, String> uuids;

    public LeaderboardData(List<CustomLeader> leadersCustom, Map<String, List<ItemRow>> leadersItems,
                           List<String> overall, Map<String, Integer> score,
                           Map<String, Integer> titles, Map<String, PlayerSummary> playerSummary,
                           Map<String, String> uuids) {
        this.leadersCustom = Collections.unmodifiableList(List.copyOf(leadersCustom));
        this.leadersItems = Collections.unmodifiableMap(new LinkedHashMap<>(leadersItems));
        this.overall = Collections.unmodifiableList(List.copyOf(overall));
        this.score = Collections.unmodifiableMap(new LinkedHashMap<>(score));
        this.titles = Collections.unmodifiableMap(new LinkedHashMap<>(titles));
        this.playerSummary = Collections.unmodifiableMap(new LinkedHashMap<>(playerSummary));
        this.uuids = Collections.unmodifiableMap(new LinkedHashMap<>(uuids));
    }

    public List<CustomLeader> getLeadersCustom() {
        return leadersCustom;
    }

    public Map<String, List<ItemRow>> getLeadersItems() {
        return leadersItems;
    }

    public List<String> getOverall() {
        return overall;
    }

    public Map<String, Integer> getScore() {
        return score;
    }

    public Map<String, Integer> getTitles() {
        return titles;
    }

    public Map<String, PlayerSummary> getPlayerSummary() {
        return playerSummary;
    }

    /** 玩家名 -> UUID（小写带连字符，与 stats 文件名一致） */
    public Map<String, String> getUuids() {
        return uuids;
    }

    public boolean isEmpty() {
        return overall.isEmpty();
    }

    public record Entry(String name, long value) {
    }

    public record CustomLeader(String stat, List<Entry> ranking) {
    }

    public record ItemRow(String stat, List<Entry> ranking, long total) {
    }

    public record PlayerSummary(long playTime, long deaths, long mobKills, long damageDealt,
                                long damageTaken, long distance, long minedTotal, long craftedTotal,
                                long aviate) {
    }
}
