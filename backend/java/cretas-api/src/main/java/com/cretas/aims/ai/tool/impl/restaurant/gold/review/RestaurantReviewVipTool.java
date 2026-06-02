package com.cretas.aims.ai.tool.impl.restaurant.gold.review;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * VIP vs 非VIP 评价对比(大众点评 星级/服务/环境)。
 * 适用意图: VIP评价情况 / 会员评价 / VIP顾客评分。
 */
@Slf4j
@Component
public class RestaurantReviewVipTool extends AbstractReviewGoldTool {

    @Override
    public String getToolName() {
        return "restaurant_review_vip";
    }

    @Override
    public String getDescription() {
        return "VIP vs 非VIP 评价对比(大众点评星级/服务/环境分)。适用: VIP评价情况/会员评价/VIP顾客评分对比。";
    }

    @Override
    protected Map<String, Object> queryGold(
            String factoryId, LocalDate start, LocalDate end, Map<String, Object> params)
            throws Exception {
        return gold.fetchReviewVip(factoryId);
    }

    @Override
    protected boolean isEmpty(Map<String, Object> g) {
        return listOfMaps(g.get("groups")).isEmpty();
    }

    @Override
    protected Map<String, Object> format(Map<String, Object> g) {
        List<Map<String, Object>> groups = listOfMaps(g.get("groups"));

        StringBuilder sb = new StringBuilder();
        sb.append("VIP vs 非VIP 评价对比：\n");
        List<String> names = new ArrayList<>();
        List<Double> vals = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        Double vipStar = null;
        Double normalStar = null;
        for (Map<String, Object> grp : groups) {
            String name = String.valueOf(grp.get("group"));
            int n = intOf(grp.get("review_count"));
            double avgStar = dbl(grp.get("avg_star"));
            double avgSvc = dbl(grp.get("avg_service"));
            sb.append("· ").append(name).append(" — ").append(n).append(" 条评价，平均 ")
                    .append(fmt2(avgStar)).append(" 星");
            if (avgSvc > 0) sb.append("，服务 ").append(fmt2(avgSvc)).append(" 分");
            sb.append("\n");

            names.add(name);
            vals.add(avgStar);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("分组", name);
            entry.put("评价数", n);
            entry.put("平均星级", avgStar);
            entry.put("平均服务分", avgSvc);
            rows.add(entry);
            if ("VIP".equals(name)) vipStar = avgStar;
            else normalStar = avgStar;
        }
        if (vipStar != null && normalStar != null) {
            double diff = vipStar - normalStar;
            if (diff < -0.05) {
                sb.append("提示：VIP 顾客评分比非VIP低 ").append(fmt2(-diff))
                        .append(" 分，VIP 顾客对体验更挑剔，建议加强 VIP 接待标准。");
            } else if (diff > 0.05) {
                sb.append("提示：VIP 顾客评分比非VIP高 ").append(fmt2(diff)).append(" 分，VIP 维护较好。");
            } else {
                sb.append("提示：VIP 与非VIP 评分基本持平。");
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("VIP评价对比", rows);
        result.put("dataAvailable", true);
        result.put("message", sb.toString());
        if (!names.isEmpty()) {
            result.put("chartConfig", barChartConfig("VIP vs 非VIP 平均星级 (分)", names, vals, "分"));
        }
        attachDepth(result,
                followups(
                        followup("VIP 喜欢什么口味", "VIP喜欢什么口味"),
                        followup("好评高频词", "好评最多提到什么"),
                        followup("差评集中点", "投诉最集中的问题"),
                        followup("整体评价总览", "客户评价怎么样")),
                glossary(
                        "VIP", "大众点评标记为会员/VIP 的顾客评价。",
                        "平均星级", "该分组所有评价星级的算术平均(满分5分)。",
                        "平均服务分", "该分组评价的服务分平均值(满分5分)。"),
                "柱对比 VIP 与非VIP 的平均星级；若 VIP 偏低说明会员更挑剔，需加强 VIP 接待标准。");
        return result;
    }

    @Override
    protected String emptyMessage() {
        return "本店暂无 VIP 标记的评价数据。请确认已上传大众点评'评价下载'报表(含是否VIP)。";
    }
}
