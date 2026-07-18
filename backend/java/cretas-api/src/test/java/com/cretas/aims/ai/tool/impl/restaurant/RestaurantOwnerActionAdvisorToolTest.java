package com.cretas.aims.ai.tool.impl.restaurant;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.client.PythonSmartBIClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RestaurantOwnerActionAdvisorToolTest {

    private static final String FACTORY_ID = "RES_DEMO_QHJ";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PythonSmartBIClient pythonSmartBIClient;
    private RestaurantOwnerActionAdvisorTool tool;

    @BeforeEach
    void setUp() throws Exception {
        pythonSmartBIClient = mock(PythonSmartBIClient.class);
        tool = new RestaurantOwnerActionAdvisorTool(pythonSmartBIClient);
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("owner advisor is a governed restaurant read tool")
    void metadata() {
        assertThat(tool.getToolName()).isEqualTo("restaurant_owner_action_advisor");
        assertThat(tool.getDomainTags()).isEqualTo(Set.of("restaurant"));
        assertThat(tool.getActionType()).isEqualTo(ToolExecutor.ActionType.READ);
        assertThat(tool.getRiskLevel()).isEqualTo(ToolExecutor.RiskLevel.LOW);
        assertThat(tool.getDescription()).contains("老板").contains("决策");
        assertThat(tool.getParametersSchema()).containsKey("properties");
    }

    @Test
    @DisplayName("owner advisor delegates to Python owner action chat and normalizes decision response")
    void delegatesToPythonOwnerActionChat() throws Exception {
        when(pythonSmartBIClient.askRestaurantOwnerActionChat(
                eq(FACTORY_ID), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Map.of(
                        "success", true,
                        "data", Map.of(
                                "sessionId", "owner-action-001",
                                "scenario", "operations_dispatch",
                                "answer", "今天先让仓管补活鱼，厨师长盯出品，前台盯核销。",
                                "followUpSuggestions", List.of("仓管具体做什么？", "明天看哪三个数？"),
                                "charts", List.of(Map.of("title", "角色动作")),
                                "roleActionPlan", List.of(Map.of("role", "仓管", "action", "补活鱼")),
                                "ownerDecisionPage", Map.of("headline", "先抓晚高峰执行")
                        )
                ));

        ToolCall call = ToolCall.of(
                "test-call",
                "restaurant_owner_action_advisor",
                objectMapper.writeValueAsString(Map.of(
                        "message", "这周营收同比上周怎么提高，仓管厨师长前台分别做什么？",
                        "sessionId", "owner-action-001",
                        "demoScenario", "operations_dispatch",
                        "storeName", "青花椒上海示范店",
                        "subSector", "中餐/川味酸菜鱼",
                        "period", "this_week"
                )));

        String json = tool.execute(call, Map.of("factoryId", FACTORY_ID, "userId", 7L));
        Map<String, Object> envelope = objectMapper.readValue(json, new TypeReference<>() {});

        assertThat(envelope).containsEntry("success", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) envelope.get("data");
        assertThat(data).containsEntry("dataAvailable", true);
        assertThat(data).containsEntry("source", "restaurant_owner_action");
        assertThat(data).containsEntry("advisorSource", "restaurant_owner_action_advisor");
        assertThat(data).containsEntry("scenario", "operations_dispatch");
        assertThat(data).containsEntry("sessionId", "owner-action-001");
        assertThat(data).containsEntry("answer", "今天先让仓管补活鱼，厨师长盯出品，前台盯核销。");
        assertThat(data).containsKey("suggestedFollowups");
        assertThat(data).containsKey("dataReadiness");

        @SuppressWarnings("unchecked")
        Map<String, Object> readiness = (Map<String, Object>) data.get("dataReadiness");
        assertThat(readiness).containsEntry("mode", "java_tool_to_python_owner_action");
        assertThat(readiness).containsEntry("factoryId", FACTORY_ID);

        ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(pythonSmartBIClient).askRestaurantOwnerActionChat(eq(FACTORY_ID), bodyCaptor.capture());
        Map<String, Object> body = bodyCaptor.getValue();
        assertThat(body).containsEntry("message", "这周营收同比上周怎么提高，仓管厨师长前台分别做什么？");
        assertThat(body).containsEntry("session_id", "owner-action-001");
        assertThat(body).containsEntry("demo_scenario", "operations_dispatch");
        assertThat(body).containsEntry("store_name", "青花椒上海示范店");
        assertThat(body).containsEntry("sub_sector", "中餐/川味酸菜鱼");
        assertThat(body).containsEntry("period", "this_week");
        assertThat(body).doesNotContainKeys("factory_id", "factoryId", "raw");
        assertThat(data).doesNotContainKey("raw");
    }

    @Test
    @DisplayName("owner advisor returns explicit unavailable result when Python is not configured")
    void unavailableWhenPythonClientMissing() throws Exception {
        RestaurantOwnerActionAdvisorTool missingClientTool = new RestaurantOwnerActionAdvisorTool(null);
        injectField(missingClientTool, "objectMapper", objectMapper);

        ToolCall call = ToolCall.of(
                "missing-client",
                "restaurant_owner_action_advisor",
                objectMapper.writeValueAsString(Map.of("message", "老板今天怎么提高营收？")));

        String json = missingClientTool.execute(call, Map.of("factoryId", FACTORY_ID, "userId", 7L));
        Map<String, Object> envelope = objectMapper.readValue(json, new TypeReference<>() {});

        assertThat(envelope).containsEntry("success", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) envelope.get("data");
        assertThat(data).containsEntry("dataAvailable", false);
        assertThat(data).containsEntry("source", "restaurant_owner_action");
        assertThat(data).containsEntry("advisorSource", "restaurant_owner_action_advisor");
        assertThat(data.get("message").toString()).contains("服务未配置");
    }

    private void injectField(Object target, String name, Object value) throws Exception {
        Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private Field findField(Class<?> clazz, String name) {
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new IllegalArgumentException("Field not found: " + name);
    }
}
