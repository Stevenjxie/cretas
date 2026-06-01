package com.cretas.aims.ai.tool.impl.restaurant.gold.review;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 投诉/差评集中点(大众点评)。
 *
 * <p>诚实标注: {@code 投诉类型} 字段是<b>商家对评价发起的申诉类型</b>(商家申诉)，
 * 样本较小; 真正的"差评"信号是 ≤3星 评价数，本工具同时给出。
 *
 * 适用意图: 投诉最集中的问题 / 主要投诉类型 / 客诉集中点。
 */
@Slf4j
@Component
public class RestaurantReviewComplaintTool extends AbstractReviewGoldTool {

    @Override
    public String getToolName() {
        return "restaurant_review_complaint";
    }

    @Override
    public String getDescription() {
        return "投诉/差评集中点(大众点评): 低星(≤3星)评价数 + 商家评价申诉类型分布。适用: 投诉最集中的问题/主要投诉类型/客诉集中点。";
    }

    @Override
    protected Map<String, Object> queryGold(
            String factoryId, LocalDate start, LocalDate end, Map<String, Object> params)
            throws Exception {
        return gold.fetchReviewComplaints(factoryId, 8);
    }

    @Override
    protected boolean isEmpty(Map<String, Object> g) {
        // Usable if there are either dispute categories OR low-star reviews to report.
        return listOfMaps(g.get("categories")).isEmpty() && intOf(g.get("low_star_count")) == 0;
    }

    @Override
    protected Map<String, Object> format(Map<String, Object> g) {
        int lowStar = intOf(g.get("low_star_count"));
        int disputes = intOf(g.get("dispute_count"));
        List<Map<String, Object>> categories = listOfMaps(g.get("categories"));

        StringBuilder sb = new StringBuilder();
        sb.append("差评与投诉分析：\n");
        sb.append("· 差评(≤3星)共 ").append(lowStar).append(" 条 —— 这是最直接的客诉信号。\n");
        if (!categories.isEmpty()) {
            sb.append("· 商家评价申诉类型分布（共 ").append(disputes)
                    .append(" 条申诉，即商家对不实评价发起的申诉）：\n");
            for (int i = 0; i < categories.size(); i++) {
                Map<String, Object> c = categories.get(i);
                sb.append("  ").append(i + 1).append(". ").append(c.get("category"))
                        .append(" — ").append(intOf(c.get("count"))).append(" 条");
                if (i < categories.size() - 1) sb.append("\n");
            }
        } else {
            sb.append("· 暂无商家评价申诉记录。");
        }

        List<String> names = new ArrayList<>();
        List<Integer> vals = new ArrayList<>();
        for (Map<String, Object> c : categories) {
            names.add(String.valueOf(c.get("category")));
            vals.add(intOf(c.get("count")));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("差评数", lowStar);
        result.put("申诉总数", disputes);
        result.put("申诉类型分布", categories);
        result.put("dataAvailable", true);
        result.put("message", sb.toString());
        if (!names.isEmpty()) {
            result.put("chartConfig", barChartConfig("商家评价申诉类型分布 (条)", names, vals, "条"));
        }
        return result;
    }

    @Override
    protected String emptyMessage() {
        return "本店暂无差评或投诉申诉记录。请确认已上传大众点评'评价下载'报表。";
    }
}
