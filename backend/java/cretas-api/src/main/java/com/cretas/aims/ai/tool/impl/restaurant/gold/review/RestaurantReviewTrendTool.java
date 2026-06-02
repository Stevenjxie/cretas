package com.cretas.aims.ai.tool.impl.restaurant.gold.review;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评价趋势(按月聚合评价量与平均星级)。
 * 适用意图: 评价趋势 / 口碑变化 / 评分走势。
 */
@Slf4j
@Component
public class RestaurantReviewTrendTool extends AbstractReviewGoldTool {

    @Override
    public String getToolName() {
        return "restaurant_review_trend";
    }

    @Override
    public String getDescription() {
        return "评价趋势(按月聚合评价量与平均星级走势)。适用: 评价趋势/口碑变化/评分走势/最近评价好转还是变差。";
    }

    @Override
    protected Map<String, Object> queryGold(
            String factoryId, LocalDate start, LocalDate end, Map<String, Object> params)
            throws Exception {
        return gold.fetchReviewTrend(factoryId);
    }

    @Override
    protected boolean isEmpty(Map<String, Object> g) {
        return listOfMaps(g.get("months")).isEmpty();
    }

    @Override
    protected Map<String, Object> format(Map<String, Object> g) {
        List<Map<String, Object>> months = listOfMaps(g.get("months"));
        int nullCount = intOf(g.get("null_period_count"));

        StringBuilder sb = new StringBuilder();
        sb.append("评价趋势（按月，平均星级满分5分）：\n");
        List<String> xLabels = new ArrayList<>();
        List<Double> starVals = new ArrayList<>();
        List<Integer> countVals = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> m : months) {
            String month = String.valueOf(m.get("month"));
            int n = intOf(m.get("review_count"));
            double avgStar = dbl(m.get("avg_star"));
            xLabels.add(month);
            starVals.add(avgStar);
            countVals.add(n);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("月份", month);
            entry.put("评价数", n);
            entry.put("平均星级", avgStar);
            rows.add(entry);
        }
        // 首尾对比结论
        if (months.size() >= 2) {
            double first = dbl(months.get(0).get("avg_star"));
            double last = dbl(months.get(months.size() - 1).get("avg_star"));
            double diff = last - first;
            sb.append("· 区间内 ").append(months.size()).append(" 个月，平均星级从 ")
                    .append(fmt2(first)).append(" 到 ").append(fmt2(last));
            if (diff > 0.05) {
                sb.append("，口碑上升 ").append(fmt2(diff)).append(" 分。");
            } else if (diff < -0.05) {
                sb.append("，口碑下降 ").append(fmt2(-diff)).append(" 分，建议排查近期门店运营。");
            } else {
                sb.append("，口碑基本平稳。");
            }
        }
        if (nullCount > 0) {
            sb.append("\n（注：").append(nullCount).append(" 条评价无时间未计入趋势）");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("评价趋势", rows);
        result.put("dataAvailable", true);
        result.put("message", sb.toString());
        // 折线图: 平均星级走势 (line option 手工构造, barChartConfig 不适用)
        if (!xLabels.isEmpty()) {
            result.put("chartConfig", trendLineConfig(xLabels, starVals, countVals));
        }
        attachDepth(result,
                followups(
                        followup("整体评价总览", "客户评价怎么样"),
                        followup("各平台口碑对比", "各平台评价对比"),
                        followup("销售月度趋势", "月度趋势"),
                        followup("差评集中点", "投诉最集中的问题")),
                glossary(
                        "评价月趋势", "按评价时间所在月份聚合的评价量与平均星级，反映口碑随时间的变化。",
                        "平均星级", "该月所有评价星级的算术平均(满分5分)。"),
                "折线为各月平均星级走势(右轴为评价量)；线下行说明近期口碑走弱，需结合该月差评排查。");
        return result;
    }

    /** 双轴折线: 左轴平均星级(line), 右轴评价量(bar)。返回 {type,title,option}. */
    private static Map<String, Object> trendLineConfig(
            List<String> x, List<Double> star, List<Integer> count) {
        Map<String, Object> opt = new LinkedHashMap<>();
        opt.put("tooltip", Map.of("trigger", "axis"));
        opt.put("legend", Map.of("data", List.of("平均星级", "评价量"), "top", "bottom"));
        opt.put("grid", Map.of("left", "3%", "right", "5%", "bottom", "12%", "top", "10%", "containLabel", true));
        opt.put("xAxis", Map.of("type", "category", "data", x,
                "axisLabel", Map.of("rotate", 30, "fontSize", 11)));
        opt.put("yAxis", List.of(
                Map.of("type", "value", "name", "星级", "min", 0, "max", 5),
                Map.of("type", "value", "name", "评价量")));
        Map<String, Object> starSeries = new LinkedHashMap<>();
        starSeries.put("name", "平均星级");
        starSeries.put("type", "line");
        starSeries.put("smooth", true);
        starSeries.put("data", star);
        starSeries.put("itemStyle", Map.of("color", "#5470c6"));
        Map<String, Object> cntSeries = new LinkedHashMap<>();
        cntSeries.put("name", "评价量");
        cntSeries.put("type", "bar");
        cntSeries.put("yAxisIndex", 1);
        cntSeries.put("data", count);
        cntSeries.put("itemStyle", Map.of("color", "#91cc75"));
        opt.put("series", List.of(starSeries, cntSeries));

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("type", "line");
        cfg.put("title", "评价月趋势 (星级 + 评价量)");
        cfg.put("option", opt);
        return cfg;
    }

    @Override
    protected String emptyMessage() {
        return "本店评价数据暂无可用时间信息，无法做趋势分析。请确认上传的大众点评'评价下载'报表含评价时间字段。";
    }
}
