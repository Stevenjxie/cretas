package com.cretas.aims.ai.tool.impl.restaurant.gold.review;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户评价总览(大众点评 评价下载 数据)。
 * 适用意图: 客户评价怎么样 / 口碑情况 / 整体评分。
 */
@Slf4j
@Component
public class RestaurantReviewSummaryTool extends AbstractReviewGoldTool {

    @Override
    public String getToolName() {
        return "restaurant_review_summary";
    }

    @Override
    public String getDescription() {
        return "客户评价总览(大众点评评价数据): 平均星级/服务/环境/口味分、评价总数、好评差评数、VIP评价数、门店与城市覆盖。适用: 客户评价怎么样/口碑情况/整体评分。";
    }

    @Override
    protected Map<String, Object> queryGold(
            String factoryId, LocalDate start, LocalDate end, Map<String, Object> params)
            throws Exception {
        return gold.fetchReviewSummary(factoryId);
    }

    @Override
    protected boolean isEmpty(Map<String, Object> g) {
        return intOf(g.get("total_reviews")) == 0;
    }

    @Override
    protected Map<String, Object> format(Map<String, Object> g) {
        int total = intOf(g.get("total_reviews"));
        double avgStar = dbl(g.get("avg_star"));
        double avgSvc = dbl(g.get("avg_service"));
        double avgEnv = dbl(g.get("avg_env"));
        double avgTaste = dbl(g.get("avg_taste"));
        int low = intOf(g.get("low_star_count"));
        int high = intOf(g.get("high_star_count"));
        int vip = intOf(g.get("vip_count"));
        int stores = intOf(g.get("store_count"));
        int cities = intOf(g.get("city_count"));

        StringBuilder sb = new StringBuilder();
        sb.append("客户评价总览（共 ").append(total).append(" 条有效评价，覆盖 ")
                .append(stores).append(" 家门店 / ").append(cities).append(" 个城市）：\n");
        sb.append("· 平均星级 ").append(fmt2(avgStar)).append(" 分");
        if (avgSvc > 0) sb.append("，服务 ").append(fmt2(avgSvc)).append(" 分");
        if (avgEnv > 0) sb.append("，环境 ").append(fmt2(avgEnv)).append(" 分");
        if (avgTaste > 0) sb.append("，口味 ").append(fmt2(avgTaste)).append(" 分");
        sb.append("\n· 好评(≥4.5星) ").append(high).append(" 条；差评(≤3星) ").append(low).append(" 条");
        sb.append("\n· VIP 顾客评价 ").append(vip).append(" 条");

        List<Map<String, Object>> dims = listOfMaps(g.get("dimension_scores"));
        List<String> names = new ArrayList<>();
        List<Double> vals = new ArrayList<>();
        for (Map<String, Object> d : dims) {
            names.add(String.valueOf(d.get("name")));
            vals.add(dbl(d.get("value")));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("评价总数", total);
        result.put("平均星级", avgStar);
        result.put("平均服务分", avgSvc);
        result.put("平均环境分", avgEnv);
        result.put("平均口味分", avgTaste);
        result.put("好评数", high);
        result.put("差评数", low);
        result.put("VIP评价数", vip);
        result.put("门店数", stores);
        result.put("城市数", cities);
        result.put("dataAvailable", true);
        result.put("message", sb.toString());
        if (!names.isEmpty()) {
            result.put("chartConfig", barChartConfig("各维度平均分 (5分制)", names, vals, "分"));
        }
        attachDepth(result,
                followups(
                        followup("差评最多门店", "差评最多的门店"),
                        followup("VIP 评价情况", "VIP评价情况"),
                        followup("好评高频词", "好评最多提到什么"),
                        followup("各平台口碑对比", "各平台评价对比")),
                glossary(
                        "星级分", "顾客对本次到店体验的总体评分(满分5分)。",
                        "服务分", "顾客对服务态度/效率的评分(满分5分)。",
                        "环境分", "顾客对就餐环境的评分(满分5分)。",
                        "口味分", "顾客对菜品口味的评分(满分5分)。",
                        "好评", "星级 >= 4.5 星的评价。",
                        "差评", "星级 <= 3 星的评价。"),
                "横轴是各维度平均分(5分制)，柱越高该维度口碑越好；对比哪个维度是短板。");
        return result;
    }

    @Override
    protected String emptyMessage() {
        return "本店暂无大众点评评价数据。请确认已在「智能分析 - Excel上传」上传'评价下载'报表。";
    }
}
