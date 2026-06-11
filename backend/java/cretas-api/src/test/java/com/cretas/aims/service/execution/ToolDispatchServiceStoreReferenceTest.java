package com.cretas.aims.service.execution;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.ai.PreprocessedQuery;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ToolDispatchService STORE resolved reference injection")
class ToolDispatchServiceStoreReferenceTest {

    @Test
    @DisplayName("resolved STORE reference injects store_name and store_id into tool params")
    void resolvedStoreReferenceInjectsStoreParams() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        CapturingTool tool = new CapturingTool(objectMapper);
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.isToolEnabledForFactory(anyString(), anyString())).thenReturn(true);
        ToolCallRedundancyService redundancy = mock(ToolCallRedundancyService.class);
        when(redundancy.isRedundant(anyString(), anyString(), anyMap())).thenReturn(false);
        when(redundancy.computeParametersHash(anyMap())).thenReturn("hash");

        ToolDispatchService service = new ToolDispatchService(
                registry,
                objectMapper,
                null,
                redundancy,
                mock(SelfCorrectionService.class),
                mock(CorrectionAgentService.class),
                mock(ExternalVerifierService.class),
                mock(ToolResultValidatorService.class),
                mock(ParameterExtractionLearningService.class));
        ReflectionTestUtils.setField(
                service,
                "writeGuardService",
                mock(com.cretas.aims.ai.tool.WriteGuardService.class));
        // W9: permissive RBAC enforcer (this test exercises reference injection, not RBAC).
        com.cretas.aims.ai.tool.ToolRbacEnforcer rbacEnforcer =
                mock(com.cretas.aims.ai.tool.ToolRbacEnforcer.class);
        when(rbacEnforcer.check(any(), any()))
                .thenReturn(com.cretas.aims.ai.tool.ToolRbacEnforcer.Decision.allow());
        when(rbacEnforcer.isAllowed(any(), any())).thenReturn(true);
        ReflectionTestUtils.setField(service, "toolRbacEnforcer", rbacEnforcer);

        PreprocessedQuery pq = PreprocessedQuery.builder()
                .originalInput("那家店的客单价呢")
                .finalQuery("门店 人民广场店的客单价呢")
                .resolvedReferences(Map.of(
                        "那家店",
                        PreprocessedQuery.ResolvedReference.of(
                                "store", "101", "人民广场店", "那家店")))
                .build();
        IntentMatchResult matchResult = IntentMatchResult.builder()
                .preprocessedQuery(pq)
                .build();
        AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode("RESTAURANT_STORE_REVENUE_RANK")
                .intentName("门店营收排行")
                .intentCategory("RESTAURANT")
                .toolName(tool.getToolName())
                .build();

        IntentExecuteResponse response = service.executeWithTool(
                tool,
                "RES_3101_009",
                IntentExecuteRequest.builder()
                        .userInput("那家店的客单价呢")
                        .sessionId("sid-store")
                        .build(),
                intent,
                9L,
                "sales_manager",
                matchResult);

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(tool.params).containsEntry("store_name", "人民广场店");
        assertThat(tool.params).containsEntry("store_id", "101");
        assertThat(tool.params).containsEntry("userInput", "门店 人民广场店的客单价呢");
        assertThat(tool.params).containsEntry("intentCode", "RESTAURANT_STORE_REVENUE_RANK");
    }

    private static class CapturingTool implements ToolExecutor {
        private final ObjectMapper objectMapper;
        private Map<String, Object> params = new HashMap<>();

        private CapturingTool(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public String getToolName() {
            return "restaurant_store_revenue_rank_gold";
        }

        @Override
        public String getDescription() {
            return "test tool";
        }

        @Override
        public Map<String, Object> getParametersSchema() {
            return Map.of("type", "object", "properties", Collections.emptyMap(), "required", Collections.emptyList());
        }

        @Override
        public String execute(ToolCall toolCall, Map<String, Object> context) throws Exception {
            params = objectMapper.readValue(
                    toolCall.getFunction().getArguments(),
                    new TypeReference<Map<String, Object>>() {});
            return """
                    {"success":true,"message":"ok","data":{"message":"ok"}}
                    """;
        }
    }

}
