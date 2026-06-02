package com.cretas.aims.ai.tool.impl.restaurant.gold.review;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 好评(>=4.5星)高频口味/品质标签(非菜名)。
 * 适用意图: 好评最多提到什么 / 顾客最满意什么 / 好评高频词。
 */
@Slf4j
@Component
public class RestaurantReviewGoodTagsTool extends AbstractReviewGoldTool {

    @Override
    public String getToolName() {
        return "restaurant_review_good_tags";
    }

    @Override
    public String getDescription() {
        return "好评(>=4.5星)中高频的口味/品质标签(大众点评菜品标签为口味描述, 非具体菜名)。适用: 好评最多提到什么/顾客最满意什么/好评高频词。";
    }

    @Override
    protected Map<String, Object> queryGold(
            String factoryId, LocalDate start, LocalDate end, Map<String, Object> params)
            throws Exception {
        return gold.fetchReviewGoodTags(factoryId, 10);
    }

    @Override
    protected boolean isEmpty(Map<String, Object> g) {
        return listOfMaps(g.get("tags")).isEmpty();
    }

    @Override
    protected Map<String, Object> format(Map<String, Object> g) {
        List<Map<String, Object>> tags = listOfMaps(g.get("tags"));
        int highStar = intOf(g.get("high_star_count"));

        StringBuilder sb = new StringBuilder();
        sb.append("好评(≥4.5星，共 ").append(highStar).append(" 条)高频口味/品质标签（非菜名）：\n");
        List<String> names = new ArrayList<>();
        List<Integer> vals = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < tags.size(); i++) {
            Map<String, Object> t = tags.get(i);
            String tag = String.valueOf(t.get("tag"));
            int n = intOf(t.get("count"));
            sb.append(i + 1).append(". ").append(tag).append("（").append(n).append(" 次）\n");
            names.add(tag);
            vals.add(n);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("标签", tag);
            entry.put("提及次数", n);
            rows.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("好评高频标签", rows);
        result.put("好评总数", highStar);
        result.put("dataAvailable", true);
        result.put("message", sb.toString());
        if (!names.isEmpty()) {
            result.put("chartConfig", barChartConfig("好评高频口味/品质标签", names, vals, "次"));
        }
        attachDepth(result,
                followups(
                        followup("差评高频词", "哪些菜品差评多"),
                        followup("VIP 喜欢什么", "VIP喜欢什么口味"),
                        followup("各平台口碑对比", "各平台评价对比"),
                        followup("整体评价总览", "客户评价怎么样")),
                glossary(
                        "口味/品质标签", "顾客在好评里勾选的口味描述词(味道好/鲜嫩/新鲜)，不是具体菜名。",
                        "好评", "星级 >= 4.5 星的评价。",
                        "提及次数", "该标签在好评中被勾选的总次数。"),
                "柱越长代表顾客在好评里提到该口味越多，是门店最受认可的味觉卖点。");
        return result;
    }

    @Override
    protected String emptyMessage() {
        return "本店暂无好评口味标签数据。请确认已上传大众点评'评价下载'报表(含菜品标签字段)。";
    }
}
