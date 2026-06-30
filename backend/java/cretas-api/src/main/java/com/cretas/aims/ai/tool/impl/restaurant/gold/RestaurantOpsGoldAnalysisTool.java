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

        Map<String, Object> response = gold.fetchRestaurantOpsAnalysis(factoryId, question, sessionId);
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
        return result;
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
