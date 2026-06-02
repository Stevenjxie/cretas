package com.cretas.aims.ai.tool.impl.restaurant.gold.review;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 各平台(点评/美团)评价量与平均星级对比。
 * 适用意图: 各平台评价对比 / 点评和美团哪个评分高 / 平台口碑。
 */
@Slf4j
@Component
public class RestaurantReviewPlatformTool extends AbstractReviewGoldTool {

    @Override
    public String getToolName() {
        return "restaurant_review_platform";
    }

    @Override
    public String getDescription() {
        return "各平台(点评/美团)评价量与平均星级对比。适用: 各平台评价对比/点评和美团哪个评分高/平台口碑。";
    }

    @Override
    protected Map<String, Object> queryGold(
            String factoryId, LocalDate start, LocalDate end, Map<String, Object> params)
            throws Exception {
        return gold.fetchReviewPlatform(factoryId);
    }

    @Override
    protected boolean isEmpty(Map<String, Object> g) {
        return listOfMaps(g.get("platforms")).isEmpty();
    }

    @Override
    protected Map<String, Object> format(Map<String, Object> g) {
        List<Map<String, Object>> platforms = listOfMaps(g.get("platforms"));

        StringBuilder sb = new StringBuilder();
        sb.append("各平台评价对比（平均星级满分5分）：\n");
        List<String> names = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> p : platforms) {
            String name = String.valueOf(p.get("platform"));
            int n = intOf(p.get("review_count"));
            double avgStar = dbl(p.get("avg_star"));
            sb.append("· ").append(name).append(" — ").append(n).append(" 条，平均 ")
                    .append(fmt2(avgStar)).append(" 星\n");
            names.add(name);
            counts.add(n);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("平台", name);
            entry.put("评价数", n);
            entry.put("平均星级", avgStar);
            rows.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("平台评价对比", rows);
        result.put("dataAvailable", true);
        result.put("message", sb.toString());
        // 评价量占比用饼图更直观
        if (!names.isEmpty()) {
            result.put("chartConfig", pieChartConfig("各平台评价量占比", names, counts));
        }
        attachDepth(result,
                followups(
                        followup("整体评价总览", "客户评价怎么样"),
                        followup("回复率情况", "评价回复率"),
                        followup("评价趋势", "评价趋势怎么样"),
                        followup("差评最多门店", "差评最多的门店")),
                glossary(
                        "平台", "评价来源渠道(大众点评 / 美团)。",
                        "评价数", "该平台去重后的有效评价条数。",
                        "平均星级", "该平台所有评价星级的算术平均(满分5分)。"),
                "扇区面积代表各平台评价量占比；结合各平台平均星级看哪个渠道口碑更优。");
        return result;
    }

    @Override
    protected String emptyMessage() {
        return "本店暂无平台来源标注的评价数据。请确认已上传大众点评'评价下载'报表(含平台字段)。";
    }
}
