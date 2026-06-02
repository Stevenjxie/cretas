package com.cretas.aims.ai.tool.impl.restaurant.gold.review;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务标签/环境标签 高频词 + 平均分。默认服务维度；用户问环境时由意图层传 dim=env。
 * 适用意图: 服务标签 / 顾客怎么评价服务 / 环境评价标签。
 */
@Slf4j
@Component
public class RestaurantReviewScoreTagsTool extends AbstractReviewGoldTool {

    @Override
    public String getToolName() {
        return "restaurant_review_score_tags";
    }

    @Override
    public String getDescription() {
        return "服务/环境评价标签高频词 + 对应平均分(大众点评服务标签/环境标签)。适用: 服务标签/顾客怎么评价服务/环境评价标签。默认服务维度。";
    }

    @Override
    protected Map<String, Object> queryGold(
            String factoryId, LocalDate start, LocalDate end, Map<String, Object> params)
            throws Exception {
        // 意图层可能在 params 注入 dim (env/service)；userInput 含"环境"则取 env。
        String dim = getString(params, "dim");
        if (dim == null || dim.isEmpty()) {
            String ui = getString(params, "userInput");
            dim = (ui != null && ui.contains("环境")) ? "env" : "service";
        }
        return gold.fetchReviewScoreTags(factoryId, dim, 10);
    }

    @Override
    protected boolean isEmpty(Map<String, Object> g) {
        return listOfMaps(g.get("tags")).isEmpty();
    }

    @Override
    protected Map<String, Object> format(Map<String, Object> g) {
        List<Map<String, Object>> tags = listOfMaps(g.get("tags"));
        String dim = String.valueOf(g.getOrDefault("dim", "service"));
        String dimName = "env".equals(dim) ? "环境" : "服务";

        StringBuilder sb = new StringBuilder();
        sb.append(dimName).append("评价标签高频词（标签后括号为提及次数 / 该标签下平均").append(dimName).append("分）：\n");
        List<String> names = new ArrayList<>();
        List<Integer> vals = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> t : tags) {
            String tag = String.valueOf(t.get("tag"));
            int n = intOf(t.get("count"));
            double avg = dbl(t.get("avg_score"));
            sb.append("· ").append(tag).append("（").append(n).append(" 次");
            if (avg > 0) {
                sb.append(" / 均分 ").append(fmt2(avg));
            }
            sb.append("）\n");
            names.add(tag);
            vals.add(n);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("标签", tag);
            entry.put("提及次数", n);
            entry.put("平均分", avg);
            rows.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put(dimName + "标签分布", rows);
        result.put("维度", dimName);
        result.put("dataAvailable", true);
        result.put("message", sb.toString());
        if (!names.isEmpty()) {
            result.put("chartConfig", barChartConfig(dimName + "评价标签高频词", names, vals, "次"));
        }
        attachDepth(result,
                followups(
                        followup("门店" + dimName + "分排名", "env".equals(dim) ? "环境分对比" : "服务分排名"),
                        followup("差评集中点", "投诉最集中的问题"),
                        followup("VIP 评价情况", "VIP评价情况"),
                        followup("整体评价总览", "客户评价怎么样")),
                glossary(
                        dimName + "标签", "顾客评价时勾选的" + dimName + "相关标签(如 服务热情/环境优雅)，由大众点评预设。",
                        "提及次数", "该标签在所有评价中被勾选的总次数。",
                        "平均分", "勾选该标签的评价对应的" + dimName + "分平均值。"),
                "柱越长代表该" + dimName + "标签被提及越多，反映顾客最常感知到的" + dimName + "特征。");
        return result;
    }

    @Override
    protected String emptyMessage() {
        return "本店暂无服务/环境评价标签数据。请确认已上传大众点评'评价下载'报表(含服务标签/环境标签字段)。";
    }
}
