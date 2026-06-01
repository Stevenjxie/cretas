package com.cretas.aims.ai.tool.impl.restaurant.gold.review;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 门店环境分对比(大众点评 环境分, 高到低)。
 * 适用意图: 环境分对比 / 哪家店环境好 / 环境评分排名。
 */
@Slf4j
@Component
public class RestaurantReviewEnvScoreTool extends AbstractReviewGoldTool {

    @Override
    public String getToolName() {
        return "restaurant_review_env_score";
    }

    @Override
    public String getDescription() {
        return "门店环境分对比(大众点评环境分, 由高到低)。适用: 环境分对比/哪家店环境好/环境评分排名。";
    }

    @Override
    protected Map<String, Object> queryGold(
            String factoryId, LocalDate start, LocalDate end, Map<String, Object> params)
            throws Exception {
        return gold.fetchReviewStoreRanking(factoryId, "env", "desc", 10, 20);
    }

    @Override
    protected boolean isEmpty(Map<String, Object> g) {
        return listOfMaps(g.get("stores")).isEmpty();
    }

    @Override
    protected Map<String, Object> format(Map<String, Object> g) {
        List<Map<String, Object>> stores = listOfMaps(g.get("stores"));

        StringBuilder sb = new StringBuilder();
        sb.append("门店环境分对比（满分5分，由高到低）：\n");
        List<String> names = new ArrayList<>();
        List<Double> vals = new ArrayList<>();
        List<Map<String, Object>> rank = new ArrayList<>();
        for (int i = 0; i < stores.size(); i++) {
            Map<String, Object> s = stores.get(i);
            String store = String.valueOf(s.get("store"));
            double env = dbl(s.get("avg_env"));
            int n = intOf(s.get("review_count"));
            sb.append(i + 1).append(". ").append(store)
                    .append(" — 环境分 ").append(fmt2(env)).append("（").append(n).append(" 条评价）");
            if (i < stores.size() - 1) sb.append("\n");

            names.add(store);
            vals.add(env);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("门店", store);
            entry.put("环境分", env);
            entry.put("评价数", n);
            rank.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("门店环境分对比", rank);
        result.put("dataAvailable", true);
        result.put("message", sb.toString());
        if (!names.isEmpty()) {
            result.put("chartConfig", barChartConfig("门店环境分 (分)", names, vals, "分"));
        }
        return result;
    }

    @Override
    protected String emptyMessage() {
        return "本店暂无门店环境分数据。请确认已上传大众点评'评价下载'报表(含环境分)。";
    }
}
