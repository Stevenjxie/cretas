package com.cretas.aims.ai.tool.impl.restaurant.gold.review;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 差评中高频的口味/品质标签(大众点评)。
 *
 * <p>诚实标注: 大众点评 {@code 菜品标签} 是<b>口味/品质描述标签</b>(如 鲜美/劲道/太软了)，
 * <b>不是具体菜名</b>。本工具统计 ≤3星 差评中出现频率最高的标签，并明确告知这一点，
 * 避免把口味标签误当作"差评菜品"。
 *
 * 适用意图: 哪些菜品差评多 / 菜品被吐槽 / 差评涉及的菜。
 */
@Slf4j
@Component
public class RestaurantReviewDishTool extends AbstractReviewGoldTool {

    @Override
    public String getToolName() {
        return "restaurant_review_dish_issue";
    }

    @Override
    public String getDescription() {
        return "差评中高频的口味/品质标签(大众点评菜品标签, 如鲜美/太软了, 非具体菜名)。适用: 哪些菜品差评多/菜品被吐槽/差评涉及的菜。";
    }

    @Override
    protected Map<String, Object> queryGold(
            String factoryId, LocalDate start, LocalDate end, Map<String, Object> params)
            throws Exception {
        return gold.fetchReviewDishIssues(factoryId, 10, 3);
    }

    @Override
    protected boolean isEmpty(Map<String, Object> g) {
        return listOfMaps(g.get("tags")).isEmpty();
    }

    @Override
    protected Map<String, Object> format(Map<String, Object> g) {
        int lowStar = intOf(g.get("low_star_count"));
        int lowWithTag = intOf(g.get("low_with_tag"));
        List<Map<String, Object>> tags = listOfMaps(g.get("tags"));

        StringBuilder sb = new StringBuilder();
        sb.append("差评(≤3星)中高频的口味/品质标签：\n");
        sb.append("（说明：大众点评的「菜品标签」是顾客对口味/品质的描述词，非具体菜名；")
                .append("以下统计自 ").append(lowStar).append(" 条差评中带标签的 ")
                .append(lowWithTag).append(" 条）\n");
        List<String> names = new ArrayList<>();
        List<Integer> vals = new ArrayList<>();
        for (int i = 0; i < tags.size(); i++) {
            Map<String, Object> t = tags.get(i);
            String tag = String.valueOf(t.get("tag"));
            int cnt = intOf(t.get("count"));
            sb.append(i + 1).append(". ").append(tag).append(" — ").append(cnt).append(" 次");
            if (i < tags.size() - 1) sb.append("\n");
            names.add(tag);
            vals.add(cnt);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("差评数", lowStar);
        result.put("带标签差评数", lowWithTag);
        result.put("高频标签", tags);
        result.put("dataAvailable", true);
        result.put("message", sb.toString());
        if (!names.isEmpty()) {
            result.put("chartConfig", barChartConfig("差评中高频口味/品质标签 (次)", names, vals, "次"));
        }
        return result;
    }

    @Override
    protected String emptyMessage() {
        return "本店低星评价较少或评价缺少菜品标签，暂无可分析的差评标签。可结合「差评最多的门店」进一步定位。";
    }
}
