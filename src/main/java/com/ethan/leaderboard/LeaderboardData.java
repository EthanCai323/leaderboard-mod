package com.ethan.leaderboard;

import java.util.List;
import java.util.Map;

/**
 * 一次排行计算的结果数据。
 */
public class LeaderboardData {
    /** 每项 custom 统计的第一名榜单（按统计 id 排序） */
    public List<CustomLeader> leadersCustom;
    /** 物品分类榜：分类 -> 前 30 项 */
    public Map<String, List<ItemRow>> leadersItems;
    /** 综合排名（玩家名，按综合分降序） */
    public List<String> overall;
    /** 玩家 -> 综合分 */
    public Map<String, Integer> score;
    /** 玩家 -> 冠军项数 */
    public Map<String, Integer> titles;
    /** 玩家 -> 关键数据总览 */
    public Map<String, PlayerSummary> playerSummary;

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
