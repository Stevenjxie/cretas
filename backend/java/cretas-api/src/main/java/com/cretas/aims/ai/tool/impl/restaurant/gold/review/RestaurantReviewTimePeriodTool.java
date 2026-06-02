package com.cretas.aims.ai.tool.impl.restaurant.gold.review;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 各时段(早/午/下午/晚/夜)评价量与平均星级。time_period ~73% 有值。
 * 适用意图: 哪个时段评价好 / 时段评价分布 / 什么时间段口碑差。
 */
@Slf4j
@Component
public class RestaurantReviewTimePeriodTool extends AbstractReviewGoldTool {

    @Override
    public String getToolName() {
        return "restaurant_review_time_period";
    }

    @Override
    public String getDescription() {
        return "各时段(早/午/下午/晚/夜)评价量与平均星级分布(大众点评评价时间)。适用: 哪个时段评价好/时段评价分布/什么时间段口碑差。";
    }

    @Override
    protected Map<String, Object> queryGold(
            String factoryId, LocalDate start, LocalDate end, Map<String, Object> params)
            throws Exception {
        return gold.fetchReviewTimePeriod(factoryId);
    }

    @Override
    protected boolean isEmpty(Map<String, Object> g) {
        return listOfMaps(g.get("periods")).isEmpty();
    }

    @Override
    protected Map<String, Object> format(Map<String, Object> g) {
        List<Map<String, Object>> periods = listOfMaps(g.get("periods"));
        int nullCount = intOf(g.get("null_period_count"));
        int total = intOf(g.get("total_reviews"));

        StringBuilder sb = new StringBuilder();
        sb.append("各时段评价分布（平均星级满分5分）：\n");
        List<String> names = new ArrayList<>();
        List<Double> vals = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> p : periods) {
            String period = String.valueOf(p.get("period"));
            int n = intOf(p.get("review_count"));
            double avgStar = dbl(p.get("avg_star"));
            sb.append("· ").append(period).append(" — ").append(n).append(" 条，平均 ")
                    .append(fmt2(avgStar)).append(" 星\n");
            names.add(period);
            vals.add(avgStar);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("时段", period);
            entry.put("评价数", n);
            entry.put("平均星级", avgStar);
            rows.add(entry);
        }
        if (nullCount > 0 && total > 0) {
            int pct = (int) Math.round(100.0 * (total - nullCount) / total);
            sb.append("（注：约 ").append(pct).append("% 评价含时间信息，其余 ")
                    .append(nullCount).append(" 条无时间未计入）");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("时段评价分布", rows);
        result.put("无时间评价数", nullCount);
        result.put("dataAvailable", true);
        result.put("message", sb.toString());
        if (!names.isEmpty()) {
            result.put("chartConfig", barChartConfig("各时段平均星级 (分)", names, vals, "分"));
        }
        attachDepth(result,
                followups(
                        followup("各时段销售峰值", "时段销售分布"),
                        followup("差评集中在哪个时段", "什么时间段口碑差"),
                        followup("整体评价总览", "客户评价怎么样"),
                        followup("门店服务分排名", "服务分排名")),
                glossary(
                        "时段划分", "早 5-10点 / 午 11-14点 / 下午 15-16点 / 晚 17-21点 / 夜 22-4点。",
                        "平均星级", "该时段所有评价的星级算术平均(满分5分)。",
                        "覆盖率", "并非所有评价都带评价时间，约 73% 有时间信息，其余不计入时段统计。"),
                "横轴时段、纵轴平均星级，柱越高该时段口碑越好；结合评价数看哪些时段既高峰又口碑稳。");
        return result;
    }

    @Override
    protected String emptyMessage() {
        return "本店评价数据暂无可用的评价时间信息，无法做时段分析。请确认上传的大众点评'评价下载'报表含评价时间字段。";
    }
}
