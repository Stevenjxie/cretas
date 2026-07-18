package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.AbstractTool;
import com.cretas.aims.ai.tool.gateway.EgressCapableTool;
import com.cretas.aims.ai.tool.gateway.ToolEgressPermit;
import com.cretas.aims.client.RestaurantOwnerActionClient;
import com.cretas.aims.client.RestaurantOwnerActionClient.OwnerActionRequest;
import com.cretas.aims.client.RestaurantOwnerActionClient.TrustedContext;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Governed Tool bridge for restaurant owner decision advice. */
@Slf4j
@Component
public class RestaurantOwnerActionAdvisorTool extends AbstractTool implements EgressCapableTool {

    private static final String DEFAULT_STORE_NAME = "青花椒上海示范店";
    private static final String DEFAULT_SUB_SECTOR = "中餐/川味酸菜鱼";
    private static final String DEFAULT_PERIOD = "this_week";
    private static final String FIXED_FAILURE = "老板动作分析暂时不可用，请稍后重试。";

    private final RestaurantOwnerActionClient ownerActionClient;

    public RestaurantOwnerActionAdvisorTool(RestaurantOwnerActionClient ownerActionClient) {
        this.ownerActionClient = ownerActionClient;
    }

    @Override
    public String getToolName() {
        return "restaurant_owner_action_advisor";
    }

    @Override
    public String getDescription() {
        return "餐饮老板决策建议工具。用于营收、排班、库存、菜品、套餐、商圈活动和差评等经营动作分析。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("message", Map.of("type", "string", "description", "老板的原始问题"));
        properties.put("sessionId", Map.of("type", "string", "description", "对话会话 ID"));
        properties.put("demoScenario", Map.of("type", "string", "description", "演示场景"));
        properties.put("storeName", Map.of("type", "string", "description", "门店名"));
        properties.put("subSector", Map.of("type", "string", "description", "餐饮赛道或菜系"));
        properties.put("period", Map.of("type", "string", "description", "分析周期"));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", Collections.emptyList());
    }

    @Override
    public String execute(ToolCall toolCall, Map<String, Object> context) {
        try {
            if (toolCall == null
                    || toolCall.getId() == null
                    || toolCall.getId().isBlank()
                    || toolCall.getFunction() == null
                    || !getToolName().equals(toolCall.getFunction().getName())) {
                throw new SecurityException("Invalid owner action tool call");
            }
            logExecutionStart(toolCall, context);
            validateContext(context);
            Map<String, Object> params = parseArgumentsWithoutPayloadLogging(toolCall);
            ToolEgressPermit permit = ToolEgressPermit.fromContext(context)
                    .orElseThrow(() -> new SecurityException(
                            "Tool egress destination is not permitted"));
            String factoryId = requireContextText(context, "factoryId");
            String userId = requirePositiveUserId(context);
            String userRole = requireContextText(context, "userRole");
            String businessType = requireContextText(context, "businessType");

            OwnerActionRequest request = new OwnerActionRequest(
                    firstNonBlank(
                            asString(params.get("message")),
                            asString(params.get("userInput")),
                            asString(params.get("query")),
                            "老板今天应该先做什么？"),
                    firstNonBlank(
                            asString(params.get("sessionId")),
                            asString(params.get("ownerActionSessionId"))),
                    firstNonBlank(
                            asString(params.get("demoScenario")),
                            asString(params.get("ownerActionScenario"))),
                    firstNonBlank(asString(params.get("storeName")), DEFAULT_STORE_NAME),
                    firstNonBlank(asString(params.get("subSector")), DEFAULT_SUB_SECTOR),
                    firstNonBlank(asString(params.get("period")), DEFAULT_PERIOD));
            Map<String, Object> pythonData = ownerActionClient.advise(
                    toolCall,
                    permit,
                    new TrustedContext(
                            factoryId,
                            userId,
                            userRole,
                            businessType,
                            toolCall.getId()),
                    request);
            String response = buildSuccessResult(normalize(factoryId, pythonData));
            logExecutionSuccess(toolCall, response);
            return response;
        } catch (Exception failure) {
            log.warn("Restaurant owner advisor failed: type={}",
                    failure.getClass().getSimpleName());
            return buildErrorResult(FIXED_FAILURE);
        }
    }

    private Map<String, Object> parseArgumentsWithoutPayloadLogging(ToolCall toolCall) {
        if (toolCall == null || toolCall.getFunction() == null) {
            throw new IllegalArgumentException("Invalid tool call");
        }
        String arguments = toolCall.getFunction().getArguments();
        if (arguments == null || arguments.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(
                    arguments, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception malformed) {
            throw new IllegalArgumentException("Invalid owner action parameters");
        }
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
        result.put("source", "restaurant_owner_action");
        result.put("advisorSource", RestaurantOwnerActionClient.TOOL_NAME);
        result.put("scenario", pythonData.get("scenario"));
        result.put("sessionId", pythonData.get("sessionId"));
        result.put("ownerDecisionPage", pythonData.getOrDefault(
                "ownerDecisionPage", Collections.emptyMap()));
        result.put("roleActionPlan", pythonData.getOrDefault(
                "roleActionPlan", Collections.emptyList()));
        result.put("charts", pythonData.getOrDefault("charts", Collections.emptyList()));
        result.put("suggestedFollowups", pythonData.getOrDefault(
                "followUpSuggestions", Collections.emptyList()));
        result.put("dataReadiness", pythonData.getOrDefault(
                "dataReadiness", dataReadiness(factoryId)));
        return result;
    }

    private Map<String, Object> dataReadiness(String factoryId) {
        Map<String, Object> readiness = new LinkedHashMap<>();
        readiness.put("mode", "java_tool_to_python_owner_action");
        readiness.put("factoryId", factoryId);
        readiness.put("sourceTypes", List.of(
                "pos_sales", "review_feedback", "inventory", "bom_cost",
                "traffic_persona_demo", "external_event_demo"));
        return readiness;
    }

    private String requireContextText(Map<String, Object> context, String key) {
        Object raw = context.get(key);
        if (raw == null || raw.toString().isBlank()) {
            throw new SecurityException("Trusted tool context is incomplete");
        }
        return raw.toString();
    }

    private String requirePositiveUserId(Map<String, Object> context) {
        Object raw = context.get("userId");
        if (!(raw instanceof Number number) || number.longValue() <= 0) {
            throw new SecurityException("Trusted tool context is incomplete");
        }
        return Long.toString(number.longValue());
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        throw new IllegalArgumentException("Owner action parameter must be a string");
    }

    private String firstNonBlank(String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    @Override
    public ActionType getActionType() {
        return ActionType.ANALYZE;
    }

    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public String getVersion() {
        return "2.0.0";
    }

    @Override
    public Set<String> getDomainTags() {
        return Set.of("restaurant", "analytics", "decision-support");
    }

    @Override
    public boolean requiresPermission() {
        return true;
    }

    @Override
    public boolean hasPermission(String userRole) {
        return false;
    }

    @Override
    public Set<String> getRequiredPermissions() {
        return Set.of("analytics:read");
    }

    @Override
    public boolean supportsPreview() {
        return false;
    }

    @Override
    public Set<String> getEgressDestinationIds() {
        return Set.of(RestaurantOwnerActionClient.DESTINATION_ID);
    }
}
