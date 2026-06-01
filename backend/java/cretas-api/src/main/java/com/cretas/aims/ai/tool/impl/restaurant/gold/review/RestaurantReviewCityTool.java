package com.cretas.aims.ai.tool.impl.restaurant.gold.review;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 各城市评价对比(大众点评 星级, 评分最低在前)。
 * 适用意图: 哪个城市评价最低 / 城市口碑对比 / 各城市评分。
 */
@Slf4j
@Component
public class RestaurantReviewCityTool extends AbstractReviewGoldTool {

    @Override
    public String getToolName() {
        return "restaurant_review_city";
    }

    @Override
    public String getDescription() {
        return "各城市评价对比(大众点评星级, 评分最低在前)。适用: 哪个城市评价最低/城市口碑对比/各城市评分。";
    }

    @Override
    protected Map<String, Object> queryGold(
            String factoryId, LocalDate start, LocalDate end, Map<String, Object> params)
            throws Exception {
        return gold.fetchReviewCityRanking(factoryId);
    }

    @Override
    protected boolean isEmpty(Map<String, Object> g) {
        return listOfMaps(g.get("cities")).isEmpty();
    }

    @Override
    protected Map<String, Object> format(Map<String, Object> g) {
        List<Map<String, Object>> cities = listOfMaps(g.get("cities"));

        StringBuilder sb = new StringBuilder();
        sb.append("各城市评价对比（平均星级，由低到高）：\n");
        List<String> names = new ArrayList<>();
        List<Double> vals = new ArrayList<>();
        List<Map<String, Object>> rank = new ArrayList<>();
        for (int i = 0; i < cities.size(); i++) {
            Map<String, Object> c = cities.get(i);
            String city = String.valueOf(c.get("city"));
            double avgStar = dbl(c.get("avg_star"));
            int n = intOf(c.get("review_count"));
            sb.append(i + 1).append(". ").append(city)
                    .append(" — 平均 ").append(fmt2(avgStar)).append(" 星（").append(n).append(" 条评价）");
            if (i < cities.size() - 1) sb.append("\n");

            names.add(city);
            vals.add(avgStar);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("城市", city);
            entry.put("平均星级", avgStar);
            entry.put("评价数", n);
            rank.add(entry);
        }
        if (!cities.isEmpty()) {
            Map<String, Object> lowest = cities.get(0);
            sb.append("\n评价最低的城市：").append(lowest.get("city"))
                    .append("（平均 ").append(fmt2(dbl(lowest.get("avg_star")))).append(" 星）");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("城市评价排行", rank);
        result.put("dataAvailable", true);
        result.put("message", sb.toString());
        if (!names.isEmpty()) {
            result.put("chartConfig", barChartConfig("各城市平均星级 (分)", names, vals, "分"));
        }
        return result;
    }

    @Override
    protected String emptyMessage() {
        return "本店暂无城市维度的评价数据。请确认已上传大众点评'评价下载'报表(含城市)。";
    }
}
