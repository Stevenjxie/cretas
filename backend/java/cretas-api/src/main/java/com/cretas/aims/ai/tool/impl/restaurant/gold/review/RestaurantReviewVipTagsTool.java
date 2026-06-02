package com.cretas.aims.ai.tool.impl.restaurant.gold.review;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * VIP vs 非VIP 高频好评/差评口味标签对比(大众点评 菜品标签=口味/品质标签, 非菜名)。
 * 适用意图: VIP喜欢什么 / VIP差评点 / 会员口味偏好。
 */
@Slf4j
@Component
public class RestaurantReviewVipTagsTool extends AbstractReviewGoldTool {

    @Override
    public String getToolName() {
        return "restaurant_review_vip_tags";
    }

    @Override
    public String getDescription() {
        return "VIP vs 非VIP 各自的高频好评/差评口味标签对比(大众点评菜品标签为口味/品质描述, 非具体菜名)。适用: VIP喜欢什么/VIP差评点/会员口味偏好。";
    }

    @Override
    protected Map<String, Object> queryGold(
            String factoryId, LocalDate start, LocalDate end, Map<String, Object> params)
            throws Exception {
        return gold.fetchReviewVipTags(factoryId, 6);
    }

    @Override
    protected boolean isEmpty(Map<String, Object> g) {
        return listOfMaps(g.get("vip_good_tags")).isEmpty()
                && listOfMaps(g.get("normal_good_tags")).isEmpty();
    }

    @Override
    protected Map<String, Object> format(Map<String, Object> g) {
        List<Map<String, Object>> vipGood = listOfMaps(g.get("vip_good_tags"));
        List<Map<String, Object>> vipBad = listOfMaps(g.get("vip_bad_tags"));
        List<Map<String, Object>> norGood = listOfMaps(g.get("normal_good_tags"));
        List<Map<String, Object>> norBad = listOfMaps(g.get("normal_bad_tags"));

        StringBuilder sb = new StringBuilder();
        sb.append("VIP vs 非VIP 口味/品质标签对比（标签为口味描述，非具体菜名）：\n");
        sb.append("· VIP 好评高频: ").append(joinTags(vipGood)).append("\n");
        sb.append("· VIP 差评高频: ").append(joinTags(vipBad)).append("\n");
        sb.append("· 非VIP 好评高频: ").append(joinTags(norGood)).append("\n");
        sb.append("· 非VIP 差评高频: ").append(joinTags(norBad));

        // 横向对比柱图: VIP 好评 top 标签量
        List<String> names = new ArrayList<>();
        List<Integer> vals = new ArrayList<>();
        for (Map<String, Object> t : vipGood) {
            names.add(String.valueOf(t.get("tag")));
            vals.add(intOf(t.get("count")));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("VIP好评标签", vipGood);
        result.put("VIP差评标签", vipBad);
        result.put("非VIP好评标签", norGood);
        result.put("非VIP差评标签", norBad);
        result.put("dataAvailable", true);
        result.put("message", sb.toString());
        if (!names.isEmpty()) {
            result.put("chartConfig", barChartConfig("VIP 好评高频口味标签", names, vals, "条"));
        }
        attachDepth(result,
                followups(
                        followup("VIP 评价情况", "VIP评价情况"),
                        followup("整体好评高频词", "好评最多提到什么"),
                        followup("差评集中点", "投诉最集中的问题"),
                        followup("VIP 在哪个时段评价", "各时段评价对比")),
                glossary(
                        "口味/品质标签", "顾客在评价里勾选的口味描述词(如 味道好/鲜嫩/太软了)，不是具体菜名。",
                        "好评", "星级 >= 4.5 星的评价。",
                        "差评", "星级 <= 3 星的评价。"),
                "柱越长代表 VIP 顾客好评里提到该口味的次数越多，反映 VIP 最看重的味觉体验。");
        return result;
    }

    private static String joinTags(List<Map<String, Object>> tags) {
        if (tags.isEmpty()) {
            return "（暂无）";
        }
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) {
                s.append("、");
            }
            s.append(tags.get(i).get("tag")).append("(").append(intOf(tags.get(i).get("count"))).append(")");
        }
        return s.toString();
    }

    @Override
    protected String emptyMessage() {
        return "本店暂无含口味标签的评价数据。请确认已在「智能分析 - Excel上传」上传大众点评'评价下载'报表(含菜品标签字段)。";
    }
}
