package com.cretas.aims.ai.tool.impl.restaurant.gold.review;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 门店服务分排名(大众点评 服务分, 高到低)。
 * 适用意图: 服务分排名 / 哪家店服务好 / 服务态度对比。
 */
@Slf4j
@Component
public class RestaurantReviewServiceScoreTool extends AbstractReviewGoldTool {

    @Override
    public String getToolName() {
        return "restaurant_review_service_score";
    }

    @Override
    public String getDescription() {
        return "门店服务分排名(大众点评服务分, 由高到低)。适用: 服务分排名/哪家店服务好/服务态度对比。";
    }

    @Override
    protected Map<String, Object> queryGold(
            String factoryId, LocalDate start, LocalDate end, Map<String, Object> params)
            throws Exception {
        return gold.fetchReviewStoreRanking(factoryId, "service", "desc", 10, 20);
    }

    @Override
    protected boolean isEmpty(Map<String, Object> g) {
        return listOfMaps(g.get("stores")).isEmpty();
    }

    @Override
    protected Map<String, Object> format(Map<String, Object> g) {
        List<Map<String, Object>> stores = listOfMaps(g.get("stores"));

        StringBuilder sb = new StringBuilder();
        sb.append("门店服务分排名（满分5分，由高到低）：\n");
        List<String> names = new ArrayList<>();
        List<Double> vals = new ArrayList<>();
        List<Map<String, Object>> rank = new ArrayList<>();
        for (int i = 0; i < stores.size(); i++) {
            Map<String, Object> s = stores.get(i);
            String store = String.valueOf(s.get("store"));
            double svc = dbl(s.get("avg_service"));
            int n = intOf(s.get("review_count"));
            sb.append(i + 1).append(". ").append(store)
                    .append(" — 服务分 ").append(fmt2(svc)).append("（").append(n).append(" 条评价）");
            if (i < stores.size() - 1) sb.append("\n");

            names.add(store);
            vals.add(svc);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("门店", store);
            entry.put("服务分", svc);
            entry.put("评价数", n);
            rank.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("门店服务分排名", rank);
        result.put("dataAvailable", true);
        result.put("message", sb.toString());
        if (!names.isEmpty()) {
            result.put("chartConfig", barChartConfig("门店服务分 (分)", names, vals, "分"));
        }
        attachDepth(result,
                followups(
                        followup("服务评价标签", "服务标签"),
                        followup("门店环境分对比", "环境分对比"),
                        followup("差评最多门店", "差评最多的门店"),
                        followup("整体评价总览", "客户评价怎么样")),
                glossary(
                        "服务分", "顾客对门店服务态度/效率的评分(满分5分)。",
                        "评价数", "该门店去重后的有效评价条数。"),
                "柱越长代表该门店服务分越高；排名靠后的门店是服务培训要优先覆盖的对象。");
        return result;
    }

    @Override
    protected String emptyMessage() {
        return "本店暂无门店服务分数据。请确认已上传大众点评'评价下载'报表(含服务分)。";
    }
}
