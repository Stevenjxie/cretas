package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Sprint 11 Round 4 P1 fix (Bug 2) — ToolDispatchService.buildNoToolResponse
 * surfaces diagnostic info instead of generic "暂不支持此类型的意图执行: RESTAURANT_OPS".
 *
 * <p>Round 3 E2E reproduced this with RESTAURANT_OPS category intent — generic
 * message was customer-visible. Mirrors PR #182 Sprint 10.5 P0 #1 fix pattern
 * (Skill 失败 surface 真实错误) and complies with
 * {@code .claude/rules/fool-proof-design.md} "4 位一体" rule (error message must
 * include actionable next-step, not generic category name).
 */
@DisplayName("ToolDispatchService.buildNoToolResponse — Sprint 11 Round 4 P1 Bug 2 fix")
class ToolDispatchServiceBuildNoToolResponseTest {

    private ToolDispatchService service;

    @BeforeEach
    void setUp() {
        // Minimal constructor wiring — buildNoToolResponse uses none of these collaborators.
        service = new ToolDispatchService(
                mock(ToolRegistry.class),
                new ObjectMapper(),
                mock(DashScopeClient.class),
                mock(ToolCallRedundancyService.class),
                mock(SelfCorrectionService.class),
                mock(CorrectionAgentService.class),
                mock(ExternalVerifierService.class),
                mock(ToolResultValidatorService.class),
                mock(ParameterExtractionLearningService.class));
    }

    // ============ Bug 2 P1 — surface diagnostic instead of generic message ============

    @Test
    @DisplayName("Round 4 Bug 2: tool_name=NULL — diagnostic mentions intent name + code + category + admin hint")
    void buildNoToolResponse_toolNameNull_mentionsIntentAndAdminHint() {
        AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode("RESTAURANT_OPS_QUERY")
                .intentName("餐饮运营查询")
                .intentCategory("RESTAURANT_OPS")
                .toolName(null)
                .build();

        IntentExecuteResponse response = service.buildNoToolResponse(intent);

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("FAILED");
        assertThat(response.getIntentRecognized()).isTrue();
        assertThat(response.getIntentCode()).isEqualTo("RESTAURANT_OPS_QUERY");
        assertThat(response.getMessage()).contains("已理解您的问题");
        assertThat(response.getMessage()).contains("管理员");
        assertThat(response.getMessage()).doesNotContain("餐饮运营查询");
        assertThat(response.getMessage()).doesNotContain("RESTAURANT_OPS_QUERY");
        assertThat(response.getMessage()).doesNotContain("RESTAURANT_OPS");
        assertThat(response.getMessage()).doesNotContain("Tool");
        assertThat(response.getFormattedText()).isEqualTo(response.getMessage());
    }

    @Test
    @DisplayName("Round 4 Bug 2: tool_name configured but Tool not in registry — diagnostic names the missing Tool")
    void buildNoToolResponse_toolNameSetButNotRegistered_mentionsToolName() {
        AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode("RESTAURANT_DISH_COST_ANALYSIS")
                .intentName("菜品成本分析")
                .intentCategory("RESTAURANT")
                .toolName("nonexistent_tool")
                .build();

        IntentExecuteResponse response = service.buildNoToolResponse(intent);

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("FAILED");
        assertThat(response.getMessage()).contains("分析能力暂时不可用");
        assertThat(response.getMessage()).contains("管理员");
        assertThat(response.getMessage()).doesNotContain("nonexistent_tool");
        assertThat(response.getMessage()).doesNotContain("菜品成本分析");
        assertThat(response.getMessage()).doesNotContain("RESTAURANT_DISH_COST_ANALYSIS");
    }

    @Test
    @DisplayName("Round 4 Bug 2: intent.intentName null fallback to intent_code in diagnostic")
    void buildNoToolResponse_nullIntentName_fallsBackToIntentCode() {
        AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode("MYSTERY_INTENT")
                .intentName(null)
                .intentCategory("UNKNOWN")
                .toolName(null)
                .build();

        IntentExecuteResponse response = service.buildNoToolResponse(intent);

        assertThat(response.getMessage()).contains("已理解您的问题");
        assertThat(response.getMessage()).doesNotContain("MYSTERY_INTENT");
        assertThat(response.getMessage()).doesNotContain("UNKNOWN");
    }

    @Test
    @DisplayName("failed tool result never exposes an English intent display name")
    void parseFailedToolResultUsesCustomerSafeChineseMessage() {
        AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode("RESTAURANT_OPS_GROSS_MARGIN")
                .intentName("Restaurant gross margin")
                .build();

        IntentExecuteResponse response = service.parseToolResultToResponse(
                "{\"success\":false}", intent);

        assertThat(response.getStatus()).isEqualTo("FAILED");
        assertThat(response.getMessage())
                .contains("没有获得可展示的结果")
                .doesNotContain("Restaurant gross margin")
                .doesNotContain("RESTAURANT_OPS_GROSS_MARGIN");
    }
}
