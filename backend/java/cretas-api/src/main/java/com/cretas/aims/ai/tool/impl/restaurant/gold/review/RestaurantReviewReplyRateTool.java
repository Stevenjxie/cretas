package com.cretas.aims.ai.tool.impl.restaurant.gold.review;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商家评价回复率(已/未回复 + 未回复差评数)。
 * 适用意图: 评价回复率 / 有多少评价没回复 / 回复及时吗。
 */
@Slf4j
@Component
public class RestaurantReviewReplyRateTool extends AbstractReviewGoldTool {

    @Override
    public String getToolName() {
        return "restaurant_review_reply_rate";
    }

    @Override
    public String getDescription() {
        return "商家评价回复率(已回复/未回复评价数 + 未回复差评数)。适用: 评价回复率/有多少评价没回复/回复及时吗。";
    }

    @Override
    protected Map<String, Object> queryGold(
            String factoryId, LocalDate start, LocalDate end, Map<String, Object> params)
            throws Exception {
        return gold.fetchReviewReplyRate(factoryId);
    }

    @Override
    protected boolean isEmpty(Map<String, Object> g) {
        return intOf(g.get("total_with_status")) == 0;
    }

    @Override
    protected Map<String, Object> format(Map<String, Object> g) {
        int replied = intOf(g.get("replied"));
        int notReplied = intOf(g.get("not_replied"));
        int notRepliedLow = intOf(g.get("not_replied_low_star"));
        double rate = dbl(g.get("reply_rate"));

        StringBuilder sb = new StringBuilder();
        sb.append("评价回复情况：\n");
        sb.append("· 回复率 ").append(fmt2(rate)).append("%（已回复 ").append(replied)
                .append(" 条 / 未回复 ").append(notReplied).append(" 条）\n");
        if (notRepliedLow > 0) {
            sb.append("· 其中有 ").append(notRepliedLow).append(" 条差评(≤3星)尚未回复，建议优先处理以挽回口碑。");
        } else {
            sb.append("· 差评均已回复，口碑维护到位。");
        }

        List<String> names = new ArrayList<>(List.of("已回复", "未回复"));
        List<Integer> vals = new ArrayList<>(List.of(replied, notReplied));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("回复率", rate);
        result.put("已回复数", replied);
        result.put("未回复数", notReplied);
        result.put("未回复差评数", notRepliedLow);
        result.put("dataAvailable", true);
        result.put("message", sb.toString());
        result.put("chartConfig", pieChartConfig("评价回复占比", names, vals));
        attachDepth(result,
                followups(
                        followup("未回复差评在哪些门店", "差评最多的门店"),
                        followup("差评集中点", "投诉最集中的问题"),
                        followup("整体评价总览", "客户评价怎么样"),
                        followup("评价趋势", "评价趋势怎么样")),
                glossary(
                        "回复率", "已回复评价数 ÷ 含回复状态的评价总数 × 100%。",
                        "未回复差评", "星级 <= 3 星且商家尚未回复的评价，属高优先级处理对象。"),
                "扇区代表已回复 vs 未回复占比；未回复差评是最该优先跟进的部分。");
        return result;
    }

    @Override
    protected String emptyMessage() {
        return "本店暂无评价回复状态数据。请确认已上传大众点评'评价下载'报表(含回复状态字段)。";
    }
}
