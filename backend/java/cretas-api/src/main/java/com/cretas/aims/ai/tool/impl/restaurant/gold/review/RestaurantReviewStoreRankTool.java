package com.cretas.aims.ai.tool.impl.restaurant.gold.review;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 差评最多的门店(按 ≤3星 评价数降序, 含差评率与平均星级)。
 * 适用意图: 差评最多的门店 / 哪家店口碑差 / 差评集中门店。
 */
@Slf4j
@Component
public class RestaurantReviewStoreRankTool extends AbstractReviewGoldTool {

    @Override
    public String getToolName() {
        return "restaurant_review_store_rank";
    }

    @Override
    public String getDescription() {
        return "差评最多的门店(大众点评评价): 按≤3星差评数降序排门店, 含差评率与平均星级。适用: 差评最多的门店/哪家店口碑差/差评集中在哪家店。";
    }

    @Override
    protected Map<String, Object> queryGold(
            String factoryId, LocalDate start, LocalDate end, Map<String, Object> params)
            throws Exception {
        return gold.fetchReviewStoreRanking(factoryId, "low_star", "desc", 10, 20);
    }

    @Override
    protected boolean isEmpty(Map<String, Object> g) {
        return listOfMaps(g.get("stores")).isEmpty();
    }

    @Override
    protected Map<String, Object> format(Map<String, Object> g) {
        List<Map<String, Object>> stores = listOfMaps(g.get("stores"));

        StringBuilder sb = new StringBuilder();
        sb.append("差评(≤3星)最多的门店：\n");
        List<String> names = new ArrayList<>();
        List<Integer> vals = new ArrayList<>();
        List<Map<String, Object>> rank = new ArrayList<>();
        for (int i = 0; i < stores.size(); i++) {
            Map<String, Object> s = stores.get(i);
            String store = String.valueOf(s.get("store"));
            int low = intOf(s.get("low_star_count"));
            int n = intOf(s.get("review_count"));
            double avgStar = dbl(s.get("avg_star"));
            double pct = n > 0 ? (low * 100.0 / n) : 0.0;
            sb.append(i + 1).append(". ").append(store)
                    .append(" — ").append(low).append(" 条差评（占比 ").append(fmt2(pct))
                    .append("%，平均 ").append(fmt2(avgStar)).append(" 星）");
            if (i < stores.size() - 1) sb.append("\n");

            names.add(store);
            vals.add(low);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("门店", store);
            entry.put("差评数", low);
            entry.put("评价总数", n);
            entry.put("差评率", Math.round(pct * 100.0) / 100.0);
            entry.put("平均星级", avgStar);
            rank.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("差评门店排行", rank);
        result.put("dataAvailable", true);
        result.put("message", sb.toString());
        if (!names.isEmpty()) {
            result.put("chartConfig", barChartConfig("差评(≤3星)数 Top 门店 (条)", names, vals, "条"));
        }
        attachDepth(result,
                followups(
                        followup("差评提到哪些口味", "哪些菜品差评多"),
                        followup("投诉集中点", "投诉最集中的问题"),
                        followup("评价趋势", "评价趋势怎么样"),
                        followup("城市评价对比", "哪个城市评价最低")),
                glossary(
                        "差评", "星级 <= 3 星的评价。",
                        "差评率", "该门店差评数 ÷ 该门店评价总数 × 100%。",
                        "平均星级", "该门店所有评价星级的算术平均(满分5分)。"),
                "柱越长代表该门店差评数越多，是口碑维护要优先关注的门店；结合差评率看是否绝对值大但占比低。");
        return result;
    }

    @Override
    protected String emptyMessage() {
        return "本店暂无足够的门店评价数据。请确认已上传大众点评'评价下载'报表。";
    }
}
