package com.cretas.aims.service.execution;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.ToolRouterService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DynamicToolSelectionServiceExecutionContextSecurityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ToolRouterService toolRouter;
    private ToolRegistry toolRegistry;
    private DynamicToolSelectionService service;
    private ToolRouterService.ToolCandidate candidate;
    private Map<String, Object> rbacContext;

    @BeforeEach
    void setUp() {
        toolRouter = mock(ToolRouterService.class);
        toolRegistry = mock(ToolRegistry.class);
        service = new DynamicToolSelectionService(toolRouter, toolRegistry, objectMapper);

        ReflectionTestUtils.setField(service, "writeGuardService", mock(WriteGuardService.class));
        ToolRbacEnforcer rbac = mock(ToolRbacEnforcer.class);
        when(rbac.isAllowed(any(), anyMap())).thenAnswer(invocation -> {
            rbacContext = new HashMap<>(invocation.getArgument(1));
            return true;
        });
        ReflectionTestUtils.setField(service, "toolRbacEnforcer", rbac);

        candidate = ToolRouterService.ToolCandidate.builder()
                .toolName("security_context_query")
                .toolDescription("test")
                .toolCategory("QUERY")
                .similarity(0.9)
                .keywords(List.of("security"))
                .build();
        when(toolRouter.retrieveCandidateTools(anyString(), any(Integer.class)))
                .thenReturn(List.of(candidate));
    }

    @Test
    void singleToolRouteRejectsClientPrincipalButPreservesBusinessContext() {
        when(toolRouter.requiresMultiToolPlan(anyString(), anyList())).thenReturn(false);
        ToolRouterService.SelectedTools selected = selectedTools();
        when(toolRouter.selectTools(anyString(), any(), anyList())).thenReturn(selected);
        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        when(toolRouter.executeToolChain(any(), contextCaptor.capture()))
                .thenReturn(Map.of("security_context_query", Map.of("message", "ok")));

        IntentExecuteResponse response = service.executeWithDynamicToolSelection(
                "F006", maliciousRequest(), intent(), null, 42L, "quality_manager");

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        Map<String, Object> context = contextCaptor.getValue();
        assertTrustedPrincipal(context);
        assertThat(context).containsEntry("storeId", "STORE-9");
        assertThat(context).containsEntry("dateRange", "last_7_days");
    }

    @Test
    void autoPlanRejectsBothClientAndPlannerPrincipalOverrides() throws Exception {
        when(toolRouter.requiresMultiToolPlan(anyString(), anyList())).thenReturn(true);
        Map<String, Object> maliciousStepParams = new HashMap<>(maliciousContext());
        maliciousStepParams.put("stepBusiness", "preserved");
        ToolRouterService.AutoPlan plan = ToolRouterService.AutoPlan.builder()
                .steps(List.of(ToolRouterService.PlanStep.builder()
                        .stepId("s1")
                        .toolName("security_context_query")
                        .params(maliciousStepParams)
                        .dependsOn(Collections.emptyList())
                        .order(1)
                        .build()))
                .executionOrder(ToolRouterService.SelectedTools.ExecutionOrder.SEQUENTIAL)
                .confidence(0.9)
                .build();
        ArgumentCaptor<Map<String, Object>> planContextCaptor = ArgumentCaptor.forClass(Map.class);
        when(toolRouter.generateExecutionPlan(anyString(), anyList(), planContextCaptor.capture()))
                .thenReturn(plan);

        CapturingTool tool = new CapturingTool(objectMapper);
        when(toolRegistry.getExecutor("security_context_query")).thenReturn(Optional.of(tool));

        IntentExecuteResponse response = service.executeWithDynamicToolSelection(
                "F006", maliciousRequest(), intent(), null, 42L, "quality_manager");

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertTrustedPrincipal(planContextCaptor.getValue());
        assertTrustedPrincipal(tool.arguments);
        assertTrustedPrincipal(tool.executionContext);
        assertTrustedPrincipal(rbacContext);
        assertThat(tool.arguments).containsEntry("storeId", "STORE-9");
        assertThat(tool.arguments).containsEntry("stepBusiness", "preserved");
    }

    private IntentExecuteRequest maliciousRequest() {
        return IntentExecuteRequest.builder()
                .userInput("inspect store")
                .context(maliciousContext())
                .build();
    }

    private Map<String, Object> maliciousContext() {
        Map<String, Object> context = new HashMap<>();
        context.put("factoryId", "EVIL_FACTORY");
        context.put("factory_id", "EVIL_FACTORY_SNAKE");
        context.put("tenantId", "EVIL_TENANT");
        context.put("tenant_id", "EVIL_TENANT_SNAKE");
        context.put("userId", 999L);
        context.put("user_id", 997L);
        context.put("actorUserId", 998L);
        context.put("actor_user_id", 996L);
        context.put("userRole", "platform_admin");
        context.put("user_role", "platform_admin_snake");
        context.put("role", "super_admin");
        context.put("storeId", "STORE-9");
        context.put("dateRange", "last_7_days");
        return context;
    }

    private AIIntentConfig intent() {
        return AIIntentConfig.builder()
                .intentCode("SECURITY_CONTEXT_QUERY")
                .intentName("Security context query")
                .intentCategory("QUERY")
                .build();
    }

    private ToolRouterService.SelectedTools selectedTools() {
        return ToolRouterService.SelectedTools.builder()
                .tools(List.of(ToolRouterService.SelectedTools.SelectedTool.builder()
                        .toolName("security_context_query")
                        .order(1)
                        .build()))
                .executionOrder(ToolRouterService.SelectedTools.ExecutionOrder.SEQUENTIAL)
                .build();
    }

    private static void assertTrustedPrincipal(Map<String, Object> actual) {
        assertThat(actual)
                .containsEntry("factoryId", "F006")
                .containsEntry("factory_id", "F006")
                .containsEntry("tenantId", "F006")
                .containsEntry("tenant_id", "F006")
                .containsEntry("userRole", "quality_manager")
                .containsEntry("user_role", "quality_manager")
                .containsEntry("role", "quality_manager");
        assertThat(((Number) actual.get("userId")).longValue()).isEqualTo(42L);
        assertThat(((Number) actual.get("user_id")).longValue()).isEqualTo(42L);
        assertThat(((Number) actual.get("actorUserId")).longValue()).isEqualTo(42L);
        assertThat(((Number) actual.get("actor_user_id")).longValue()).isEqualTo(42L);
    }

    private static final class CapturingTool implements ToolExecutor {
        private final ObjectMapper objectMapper;
        private Map<String, Object> arguments = new HashMap<>();
        private Map<String, Object> executionContext = new HashMap<>();

        private CapturingTool(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public String getToolName() {
            return "security_context_query";
        }

        @Override
        public String getDescription() {
            return "test";
        }

        @Override
        public Map<String, Object> getParametersSchema() {
            return Map.of("type", "object", "properties", Collections.emptyMap(),
                    "required", Collections.emptyList());
        }

        @Override
        public String execute(ToolCall toolCall, Map<String, Object> context) throws Exception {
            arguments = objectMapper.readValue(
                    toolCall.getFunction().getArguments(),
                    new TypeReference<Map<String, Object>>() { });
            executionContext = new HashMap<>(context);
            return "{\"success\":true,\"message\":\"ok\"}";
        }
    }
}
