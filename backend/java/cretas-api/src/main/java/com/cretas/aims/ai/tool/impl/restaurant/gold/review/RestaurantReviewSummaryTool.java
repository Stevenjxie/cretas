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
        Map<String, Object> summary = new LinkedHashMap<>(gold.fetchReviewSummary(factoryId));
        try {
            Map<String, Object> tags = gold.fetchReviewVipTags(factoryId, 5);
            summary.put("vip_good_tags", tags.get("vip_good_tags"));
            summary.put("vip_bad_tags", tags.get("vip_bad_tags"));
            summary.put("normal_good_tags", tags.get("normal_good_tags"));
            summary.put("normal_bad_tags", tags.get("normal_bad_tags"));
        } catch (Exception e) {
            log.warn("Fetch review high-frequency tags failed, keep summary only: factoryId={}, error={}",
                    factoryId, e.getMessage());
        }
        summary.put("userInput", getString(params, "userInput"));
        return summary;
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

        List<Map<String, Object>> vipGood = listOfMaps(g.get("vip_good_tags"));
        List<Map<String, Object>> vipBad = listOfMaps(g.get("vip_bad_tags"));
        List<Map<String, Object>> normalGood = listOfMaps(g.get("normal_good_tags"));
        List<Map<String, Object>> normalBad = listOfMaps(g.get("normal_bad_tags"));
        String userInput = String.valueOf(g.getOrDefault("userInput", ""));
        boolean focusedHighFrequency = userInput.contains("高频")
                && (userInput.contains("好评") || userInput.contains("差评") || userInput.contains("分别"));
        if (!vipGood.isEmpty() || !normalGood.isEmpty() || !vipBad.isEmpty() || !normalBad.isEmpty()) {
            if (focusedHighFrequency) {
                sb = new StringBuilder();
                sb.append("大众点评高频好评/差评词拆解（共 ").append(total).append(" 条有效评价）：\n");
                sb.append("· 高频好评词：VIP ").append(joinTags(vipGood))
                        .append("；非VIP ").append(joinTags(normalGood));
                sb.append("\n· 高频差评词：VIP ").append(joinTags(vipBad))
                        .append("；非VIP ").append(joinTags(normalBad));
                sb.append("\n老板今天怎么用：好评词放到点评/美团首图和门口海报，差评词拆给店长、厨师长、前台分别处理。");
                sb.append("\n明天只看三项：首图点击有没有涨、相关差评词有没有降、店长是否按差评词完成整改。");
            } else {
                sb.append("\n· 高频好评词：VIP ").append(joinTags(vipGood))
                        .append("；非VIP ").append(joinTags(normalGood));
                sb.append("\n· 高频差评词：VIP ").append(joinTags(vipBad))
                        .append("；非VIP ").append(joinTags(normalBad));
                sb.append("\n建议：好评词用于平台首图和门口卖点，差评词当天拆给店长/厨师长/前台整改，不要只看平均分。");
            }
        }

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
        result.put("VIP好评标签", vipGood);
        result.put("VIP差评标签", vipBad);
        result.put("非VIP好评标签", normalGood);
        result.put("非VIP差评标签", normalBad);
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

    private static String joinTags(List<Map<String, Object>> tags) {
        if (tags == null || tags.isEmpty()) {
            return "（暂无）";
        }
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) {
                s.append("、");
            }
            Map<String, Object> tag = tags.get(i);
            s.append(tag.get("tag")).append("(").append(intOf(tag.get("count"))).append(")");
        }
        return s.toString();
    }

    @Override
    protected String emptyMessage() {
        return "本店暂无大众点评评价数据。请确认已在「智能分析 - Excel上传」上传'评价下载'报表。";
    }
}
