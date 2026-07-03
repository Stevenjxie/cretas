package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.client.PythonSmartBIClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Governed Tool bridge for restaurant owner decision advice.
 *
 * <p>The actual analysis remains in Python's owner-action section. This Tool
 * makes the capability visible to Java Tool governance, domain filtering, and
 * future Skill composition instead of keeping it as an orchestrator-only branch.
 */
@Component
public class RestaurantOwnerActionAdvisorTool extends AbstractBusinessTool {

    private static final String DEFAULT_STORE_NAME = "青花椒上海示范店";
    private static final String DEFAULT_SUB_SECTOR = "中餐/川味酸菜鱼";
    private static final String DEFAULT_PERIOD = "this_week";

    private final PythonSmartBIClient pythonSmartBIClient;

    @Autowired
    public RestaurantOwnerActionAdvisorTool(PythonSmartBIClient pythonSmartBIClient) {
        this.pythonSmartBIClient = pythonSmartBIClient;
    }

    @Override
    public String getToolName() {
        return "restaurant_owner_action_advisor";
    }

    @Override
    public String getDescription() {
        return "餐饮老板决策建议工具。用于营收提升、仓管/厨师长/前台分工、排班、库存、桌型、套餐、商圈活动、点评差评等老板动作建议。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("message", Map.of("type", "string", "description", "老板的原始问题"));
        properties.put("sessionId", Map.of("type", "string", "description", "owner-action 对话会话 ID，可选"));
        properties.put("demoScenario", Map.of("type", "string", "description", "指定演示场景，可选"));
        properties.put("storeName", Map.of("type", "string", "description", "门店名，可选"));
        properties.put("subSector", Map.of("type", "string", "description", "餐饮赛道/菜系，可选"));
        properties.put("period", Map.of("type", "string", "description", "分析周期，可选"));

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
            Map<String, Object> context) {

        if (pythonSmartBIClient == null) {
            return unavailable(factoryId, "老板动作分析服务未配置，请稍后重试。");
        }

        String message = firstNonBlank(
                asString(params.get("message")),
                asString(params.get("userInput")),
                asString(params.get("query")),
                "老板今天应该怎么提高营收？");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("factory_id", factoryId);
        body.put("factoryId", factoryId);
        body.put("message", message);
        putIfPresent(body, "sessionId", firstNonBlank(asString(params.get("sessionId")), asString(params.get("ownerActionSessionId"))));
        putIfPresent(body, "demoScenario", firstNonBlank(asString(params.get("demoScenario")), asString(params.get("ownerActionScenario"))));
        body.put("storeName", firstNonBlank(asString(params.get("storeName")), DEFAULT_STORE_NAME));
        body.put("subSector", firstNonBlank(asString(params.get("subSector")), DEFAULT_SUB_SECTOR));
        body.put("period", firstNonBlank(asString(params.get("period")), DEFAULT_PERIOD));

        Map<String, Object> raw = pythonSmartBIClient.askRestaurantOwnerActionChat(body);
        boolean ok = Boolean.TRUE.equals(raw.get("success"));
        Object dataObj = raw.get("data");
        if (!ok || !(dataObj instanceof Map<?, ?> dataRaw)) {
            String error = firstNonBlank(asString(raw.get("message")), "老板动作分析暂时没有返回，请稍后重试。");
            return unavailable(factoryId, error);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> pythonData = new LinkedHashMap<>((Map<String, Object>) dataRaw);
        return normalize(factoryId, pythonData);
    }

    private Map<String, Object> normalize(String factoryId, Map<String, Object> pythonData) {
        Map<String, Object> result = new LinkedHashMap<>();
        String answer = firstNonBlank(
                asString(pythonData.get("responseText")),
                asString(pythonData.get("answer")),
                "已生成老板决策建议。");

        result.put("dataAvailable", true);
        result.put("message", answer);
        result.put("answer", answer);
        result.put("source", "restaurant_owner_action_advisor");
        result.put("scenario", pythonData.get("scenario"));
        result.put("sessionId", pythonData.get("sessionId"));
        result.put("ownerDecisionPage", pythonData.getOrDefault("ownerDecisionPage", Collections.emptyMap()));
        result.put("roleActionPlan", pythonData.getOrDefault("roleActionPlan", Collections.emptyList()));
        result.put("charts", pythonData.getOrDefault("charts", Collections.emptyList()));
        result.put("suggestedFollowups", pythonData.getOrDefault(
                "suggestedFollowups",
                pythonData.getOrDefault("followUpSuggestions", Collections.emptyList())));
        result.put("dataReadiness", pythonData.getOrDefault(
                "dataReadiness",
                dataReadiness(factoryId, "java_tool_to_python_owner_action")));
        result.put("raw", pythonData);
        return result;
    }

    private Map<String, Object> unavailable(String factoryId, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dataAvailable", false);
        result.put("message", message);
        result.put("answer", message);
        result.put("source", "restaurant_owner_action_advisor");
        result.put("charts", Collections.emptyList());
        result.put("roleActionPlan", Collections.emptyList());
        result.put("suggestedFollowups", Collections.emptyList());
        result.put("dataReadiness", dataReadiness(factoryId, "java_tool_unavailable"));
        return result;
    }

    private Map<String, Object> dataReadiness(String factoryId, String mode) {
        Map<String, Object> readiness = new LinkedHashMap<>();
        readiness.put("mode", mode);
        readiness.put("factoryId", factoryId);
        readiness.put("sourceTypes", List.of(
                "pos_sales",
                "review_feedback",
                "inventory",
                "bom_cost",
                "traffic_persona_demo",
                "external_event_demo"));
        readiness.put("confidenceNote", "Java Tool 已接入 Python 老板动作分析；生产环境应继续标注真实数据、mock 数据和外部数据来源。");
        return readiness;
    }

    private void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private String firstNonBlank(String... values) {
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
