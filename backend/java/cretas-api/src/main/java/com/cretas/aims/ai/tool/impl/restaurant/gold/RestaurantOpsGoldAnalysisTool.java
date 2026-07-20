package com.cretas.aims.ai.tool.impl.restaurant.gold;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.client.GoldFinanceClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only bridge from Java AI intents to Python restaurant-ops Gold analysis.
 */
@Component
public class RestaurantOpsGoldAnalysisTool extends AbstractBusinessTool {

    private final GoldFinanceClient gold;

    @Autowired
    public RestaurantOpsGoldAnalysisTool(GoldFinanceClient gold) {
        this.gold = gold;
    }

    @Override
    public String getToolName() {
        return "restaurant_ops_gold_analysis";
    }

    @Override
    public String getDescription() {
        return "餐饮经营 ops gold 分析工具。适用于领料趋势、损耗排名、盘亏热点、菜品成本、毛利、门店毛利、营收趋势等问题，返回真实聚合数据、白话分析和图表。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("userInput", Map.of(
                "type", "string",
                "description", "用户原始问题"));
        properties.put("intentCode", Map.of(
                "type", "string",
                "description", "当前命中的意图代码"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(
            String factoryId,
            Map<String, Object> params,
            Map<String, Object> context) throws Exception {
        String question = firstNonBlank(
                asString(params.get("userInput")),
                asString(params.get("query")),
                asString(params.get("message")));
        if (question == null) {
            question = "分析餐饮经营情况";
        }

        Object requestObj = context != null ? context.get("request") : null;
        String sessionId = null;
        if (requestObj instanceof com.cretas.aims.dto.ai.IntentExecuteRequest req) {
            sessionId = req.getSessionId();
        }

        Map<String, Object> response = gold.fetchRestaurantOpsAnalysis(
                factoryId, question, sessionId, asString(params.get("intentCode")));
        boolean success = !Boolean.FALSE.equals(response.get("success"));
        String answer = firstNonBlank(
                asString(response.get("answer")),
                asString(response.get("aiAnalysis")),
                asString(response.get("message")),
                asString(response.get("error")));

        if (answer == null || answer.isBlank()) {
            answer = "餐饮经营分析已完成，但返回内容为空。请换一个更具体的问题，例如“损耗金额排名和原因占比”或“最近哪些食材盘亏最严重”。";
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dataAvailable", success);
        result.put("message", answer);
        result.put("answer", answer);
        result.put("source", "restaurant_ops_gold");
        result.put("intentCode", params.get("intentCode"));
        result.put("charts", response.getOrDefault("charts", Collections.emptyList()));
        result.put("insights", response.getOrDefault("insights", Collections.emptyList()));
        result.put("processingTimeMs", response.getOrDefault("processing_time_ms", 0));
        String scenario = ownerActionScenario(question, asString(params.get("intentCode")));
        result.put("decisionBridge", decisionBridge(scenario));
        result.put("suggestedFollowups", decisionFollowups(scenario));
        return result;
    }

    private static String ownerActionScenario(String question, String intentCode) {
        String text = ((question == null ? "" : question) + " " + (intentCode == null ? "" : intentCode)).toLowerCase();
        if (text.contains("损耗") || text.contains("盘亏") || text.contains("领料") || text.contains("成本")
                || text.contains("毛利") || text.contains("采购") || text.contains("bom")) {
            return "cost_margin";
        }
        if (text.contains("门店") || text.contains("哪家店") || text.contains("排行") || text.contains("对比")) {
            return "store_compare";
        }
        if (text.contains("趋势") || text.contains("营收") || text.contains("销售") || text.contains("同比")
                || text.contains("环比")) {
            return "external_event_response";
        }
        return "store_compare";
    }

    private static Map<String, Object> decisionBridge(String scenario) {
        Map<String, Object> bridge = new LinkedHashMap<>();
        bridge.put("answerMode", "report_with_owner_action");
        bridge.put("ownerActionScenario", scenario);
        bridge.put("plainDecision", "这条是经营报表回答；如果老板继续追问，我会基于同一份数据直接给今天动作、不要做什么和明天看什么。");
        return bridge;
    }

    private static List<Map<String, Object>> decisionFollowups(String scenario) {
        return List.of(
                followup("老板今天怎么做？", "老板今天怎么用这张报表做决定？", scenario),
                followup("先别做什么？", "哪些动作今天先不要做？", scenario),
                followup("明天看什么数？", "明天看哪三个数判断有没有效果？", scenario)
        );
    }

    private static Map<String, Object> followup(String label, String question, String scenario) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("label", label);
        f.put("question", question);
        f.put("ownerActionScenario", scenario);
        return f;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
