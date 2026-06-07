package com.cretas.aims.service.execution;

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
import com.cretas.aims.ai.dto.ToolCall;
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

@DisplayName("ToolDispatchService DISH resolved reference injection")
class ToolDispatchServiceDishReferenceTest {

    @Test
    @DisplayName("resolved DISH reference injects dish_name and dish_id into tool params")
    void resolvedDishReferenceInjectsDishParams() throws Exception {
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

        PreprocessedQuery pq = PreprocessedQuery.builder()
                .originalInput("那道菜的销售趋势呢")
                .finalQuery("菜品 叮咚卤猪蹄的销售趋势呢")
                .resolvedReferences(Map.of(
                        "那道菜",
                        PreprocessedQuery.ResolvedReference.of(
                                "dish", "201", "叮咚卤猪蹄", "那道菜")))
                .build();
        IntentMatchResult matchResult = IntentMatchResult.builder()
                .preprocessedQuery(pq)
                .build();
        AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode("RESTAURANT_BESTSELLER_QUERY")
                .intentName("畅销菜品查询")
                .intentCategory("RESTAURANT")
                .toolName(tool.getToolName())
                .build();

        IntentExecuteResponse response = service.executeWithTool(
                tool,
                "RES_3101_009",
                IntentExecuteRequest.builder()
                        .userInput("那道菜的销售趋势呢")
                        .sessionId("sid-dish")
                        .build(),
                intent,
                9L,
                "sales_manager",
                matchResult);

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(tool.params).containsEntry("dish_name", "叮咚卤猪蹄");
        assertThat(tool.params).containsEntry("dish_id", "201");
        assertThat(tool.params).containsEntry("userInput", "菜品 叮咚卤猪蹄的销售趋势呢");
        assertThat(tool.params).containsEntry("intentCode", "RESTAURANT_BESTSELLER_QUERY");
    }

    private static class CapturingTool implements ToolExecutor {
        private final ObjectMapper objectMapper;
        Map<String, Object> params = new HashMap<>();

        private CapturingTool(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public String getToolName() {
            return "restaurant_dish_bestseller_gold";
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
