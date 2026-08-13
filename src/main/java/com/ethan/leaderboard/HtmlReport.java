package com.ethan.leaderboard;

import java.util.List;
import java.util.Map;

/**
 * HTML 页面生成，模板与结构 1:1 移植自 leaderboard.py。
 */
public final class HtmlReport {

    private static final String HTML_TEMPLATE = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>MC 服务器排行榜</title>
            <style>
              body { font-family:"Microsoft YaHei","Segoe UI",sans-serif; background:#f7f7f5; color:#262626; margin:0; }
              .wrap { max-width:960px; margin:0 auto; padding:28px 20px 48px; }
              h1 { font-size:22px; margin:0; font-weight:600; }
              .meta { color:#999; font-size:13px; margin-top:6px; }
              nav { margin-top:22px; border-bottom:1px solid #ddd; }
              nav button { background:none; border:none; padding:10px 16px; font-size:14px; color:#777; cursor:pointer; border-bottom:2px solid transparent; margin-bottom:-1px; }
              nav button:hover { color:#333; }
              nav button.active { color:#2e7d32; border-bottom-color:#2e7d32; font-weight:600; }
              .tab { display:none; padding-top:20px; }
              .tab.active { display:block; }
              h2 { font-size:16px; margin:26px 0 8px; font-weight:600; }
              table { width:100%; border-collapse:collapse; font-size:14px; background:#fff; }
              th, td { text-align:left; padding:8px 12px; border-bottom:1px solid #eee; }
              th { color:#999; font-weight:normal; font-size:13px; border-bottom:1px solid #ddd; }
              .rank { width:44px; text-align:center; color:#999; }
              .r1 { color:#2e7d32; font-weight:600; }
              .first { color:#2e7d32; font-weight:600; }
            </style>
            </head>
            <body>
            <div class="wrap">
            <h1>MC 服务器排行榜</h1>
            <div class="meta">更新于 __UPDATED__ ｜ __PCOUNT__ 名玩家</div>
            <nav>
              <button class="active" onclick="showTab(0,this)">综合排行榜</button>
              <button onclick="showTab(1,this)">数据之王</button>
              <button onclick="showTab(2,this)">物品分类榜</button>
              <button onclick="showTab(3,this)">玩家数据总览</button>
            </nav>

            <div class="tab active">
              __OVERALL_TABLE__
            </div>

            <div class="tab">
              __LEADER_TABLE__
            </div>

            <div class="tab">
              __ITEM_TABLES__
            </div>

            <div class="tab">
              __SUMMARY_TABLE__
            </div>

            </div>
            <script>
            function showTab(i, btn){
              document.querySelectorAll('.tab').forEach((t,j)=>t.classList.toggle('active', i===j));
              document.querySelectorAll('nav button').forEach(b=>b.classList.remove('active'));
              btn.classList.add('active');
            }
            </script>
            </body>
            </html>
            """;

    private HtmlReport() {
    }

    public static String buildHtml(LeaderboardData data, String updated) {
        // --- 综合排行榜 ---
        StringBuilder rows = new StringBuilder();
        int i = 0;
        for (String name : data.overall) {
            i++;
            LeaderboardData.PlayerSummary ps = data.playerSummary.get(name);
            rows.append("<tr><td class=\"rank r").append(i).append("\">").append(i).append("</td>")
                    .append("<td><b>").append(StatFormat.esc(name)).append("</b></td>")
                    .append("<td><b>").append(data.score.get(name)).append("</b></td>")
                    .append("<td>").append(data.titles.get(name)).append("</td>")
                    .append("<td>").append(StatFormat.formatValue("minecraft:play_time", ps.playTime())).append("</td>")
                    .append("<td>").append(ps.deaths()).append("</td>")
                    .append("<td>").append(StatFormat.num(ps.mobKills())).append("</td>")
                    .append("<td>").append(StatFormat.num(ps.minedTotal())).append("</td></tr>");
        }
        String overallTable = "<table><tr><th>#</th><th>玩家</th><th>综合分</th><th>冠军项数</th>"
                + "<th>游戏时间</th><th>死亡</th><th>击杀生物</th><th>总挖掘</th></tr>"
                + rows + "</table>";

        // --- 数据之王 ---
        StringBuilder krows = new StringBuilder();
        for (LeaderboardData.CustomLeader leader : data.leadersCustom) {
            String stat = leader.stat();
            List<LeaderboardData.Entry> ranking = leader.ranking();
            LeaderboardData.Entry king = ranking.get(0);
            String runner = ranking.size() > 1
                    ? StatFormat.esc(ranking.get(1).name()) + " · " + StatFormat.formatValue(stat, ranking.get(1).value())
                    : "—";
            krows.append("<tr><td>").append(StatFormat.esc(StatFormat.statDisplayName(stat))).append("</td>")
                    .append("<td class=\"first\">").append(StatFormat.esc(king.name())).append("</td>")
                    .append("<td>").append(StatFormat.formatValue(stat, king.value())).append("</td>")
                    .append("<td>").append(runner).append("</td></tr>");
        }
        String leaderTable = "<table><tr><th>统计项</th><th>第一名</th><th>数值</th><th>第二名</th></tr>"
                + krows + "</table>";

        // --- 物品分类榜 ---
        StringBuilder sections = new StringBuilder();
        for (Map.Entry<String, List<LeaderboardData.ItemRow>> catEntry : data.leadersItems.entrySet()) {
            String cat = catEntry.getKey();
            List<LeaderboardData.ItemRow> rowsCat = catEntry.getValue();
            if (rowsCat.isEmpty()) {
                continue;
            }
            StringBuilder trs = new StringBuilder();
            int limit = Math.min(StatFormat.TOP_ITEMS_PER_CATEGORY, rowsCat.size());
            for (LeaderboardData.ItemRow row : rowsCat.subList(0, limit)) {
                String stat = row.stat();
                List<LeaderboardData.Entry> ranking = row.ranking();
                LeaderboardData.Entry king = ranking.get(0);
                String runner = ranking.size() > 1
                        ? StatFormat.esc(ranking.get(1).name()) + " · " + StatFormat.formatValue(stat, ranking.get(1).value())
                        : "—";
                trs.append("<tr><td>").append(StatFormat.esc(StatFormat.prettifyId(stat))).append("</td>")
                        .append("<td class=\"first\">").append(StatFormat.esc(king.name())).append("</td>")
                        .append("<td>").append(StatFormat.formatValue(stat, king.value())).append("</td>")
                        .append("<td>").append(runner).append("</td>")
                        .append("<td>").append(StatFormat.num(row.total())).append("</td></tr>");
            }
            sections.append("<h2>").append(StatFormat.esc(StatFormat.CATEGORY_NAMES.get(cat))).append("</h2>")
                    .append("<table><tr><th>项目</th><th>第一名</th><th>数量</th><th>第二名</th><th>全员总量</th></tr>")
                    .append(trs).append("</table>");
        }
        String itemTables = sections.length() > 0 ? sections.toString() : "<p>暂无数据</p>";

        // --- 玩家数据总览 ---
        StringBuilder srows = new StringBuilder();
        for (String name : data.overall) {
            LeaderboardData.PlayerSummary ps = data.playerSummary.get(name);
            srows.append("<tr><td><b>").append(StatFormat.esc(name)).append("</b></td>")
                    .append("<td>").append(StatFormat.formatValue("minecraft:play_time", ps.playTime())).append("</td>")
                    .append("<td>").append(ps.deaths()).append("</td>")
                    .append("<td>").append(StatFormat.num(ps.mobKills())).append("</td>")
                    .append("<td>").append(StatFormat.formatValue("minecraft:damage_dealt", ps.damageDealt())).append("</td>")
                    .append("<td>").append(StatFormat.formatValue("minecraft:damage_taken", ps.damageTaken())).append("</td>")
                    .append("<td>").append(StatFormat.formatValue("minecraft:walk_one_cm", ps.distance())).append("</td>")
                    .append("<td>").append(StatFormat.num(ps.minedTotal())).append("</td>")
                    .append("<td>").append(StatFormat.num(ps.craftedTotal())).append("</td></tr>");
        }
        String summaryTable = "<table><tr><th>玩家</th><th>游戏时间</th><th>死亡</th><th>击杀生物</th>"
                + "<th>造成伤害</th><th>承受伤害</th><th>总移动距离</th><th>总挖掘</th><th>总合成</th></tr>"
                + srows + "</table>";

        return HTML_TEMPLATE
                .replace("__UPDATED__", updated)
                .replace("__PCOUNT__", String.valueOf(data.overall.size()))
                .replace("__OVERALL_TABLE__", overallTable)
                .replace("__LEADER_TABLE__", leaderTable)
                .replace("__ITEM_TABLES__", itemTables)
                .replace("__SUMMARY_TABLE__", summaryTable);
    }
}
