package com.cretas.aims.service.intent.impl;

import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.learning.LearnedExpression;
import com.cretas.aims.service.ExpressionLearningService;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.mockito.Mockito.*;

class AutoLearnPoisonGuardTest {

    private AIIntentConfig intent(String code, String biz) {
        return intent(code, biz, null);
    }

    /**
     * Factory that also sets toolName.
     * Guard B in tryAutoLearnExpression blocks learning when toolName is null/blank
     * (dead-route poison guard). Tests that expect learning to proceed must supply
     * a non-blank toolName so Guard B passes.
     */
    private AIIntentConfig intent(String code, String biz, String toolName) {
        AIIntentConfig i = new AIIntentConfig();
        i.setIntentCode(code);
        i.setBusinessType(biz);
        i.setToolName(toolName);
        return i;
    }

    private IntentRecognitionPipelineServiceImpl irpWith(
            IntentConfigManagementService cfg, ExpressionLearningService els) {
        IntentRecognitionPipelineServiceImpl irp =
            mock(IntentRecognitionPipelineServiceImpl.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(irp, "configService", cfg);
        ReflectionTestUtils.setField(irp, "expressionLearningService", els);
        return irp;
    }

    @Test
    void rejects_incompatibleIntent_forRestaurantFactory() {
        IntentConfigManagementService cfg = mock(IntentConfigManagementService.class);
        ExpressionLearningService els = mock(ExpressionLearningService.class);
        when(cfg.resolveBusinessDomain("RES_1")).thenReturn("RESTAURANT");
        when(cfg.getAllIntents("RES_1")).thenReturn(List.of(intent("SKU_GROSS_MARGIN", "FACTORY")));
        IntentRecognitionPipelineServiceImpl irp = irpWith(cfg, els);
        ReflectionTestUtils.invokeMethod(irp, "tryAutoLearnExpression",
            "付款方式占比", "SKU_GROSS_MARGIN", "RES_1", 0.9, LearnedExpression.SourceType.LLM_FALLBACK);
        verify(els, never()).learnExpression(any(), any(), any(), anyDouble(), any());
    }

    @Test
    void allows_compatibleIntent() {
        // Guard B requires toolName non-blank; supply one so the compatible-intent path proceeds.
        IntentConfigManagementService cfg = mock(IntentConfigManagementService.class);
        ExpressionLearningService els = mock(ExpressionLearningService.class);
        when(cfg.resolveBusinessDomain("RES_1")).thenReturn("RESTAURANT");
        when(cfg.getAllIntents("RES_1")).thenReturn(
            List.of(intent("RESTAURANT_ORDER_STATISTICS", "RESTAURANT", "restaurant_order_statistics")));
        IntentRecognitionPipelineServiceImpl irp = irpWith(cfg, els);
        ReflectionTestUtils.invokeMethod(irp, "tryAutoLearnExpression",
            "外送占比", "RESTAURANT_ORDER_STATISTICS", "RES_1", 0.9, LearnedExpression.SourceType.LLM_FALLBACK);
        verify(els).learnExpression(eq("RES_1"), eq("RESTAURANT_ORDER_STATISTICS"), eq("外送占比"),
            eq(0.9), eq(LearnedExpression.SourceType.LLM_FALLBACK));
    }

    @Test
    void allows_whenIntentNotFoundInConfig() {
        // unknown intentCode → cfg lookup returns no match → guard does not block (conservative)
        IntentConfigManagementService cfg = mock(IntentConfigManagementService.class);
        ExpressionLearningService els = mock(ExpressionLearningService.class);
        when(cfg.resolveBusinessDomain("RES_1")).thenReturn("RESTAURANT");
        when(cfg.getAllIntents("RES_1")).thenReturn(List.of());
        IntentRecognitionPipelineServiceImpl irp = irpWith(cfg, els);
        ReflectionTestUtils.invokeMethod(irp, "tryAutoLearnExpression",
            "foo", "UNKNOWN_INTENT", "RES_1", 0.9, LearnedExpression.SourceType.LLM_FALLBACK);
        verify(els).learnExpression(any(), eq("UNKNOWN_INTENT"), any(), anyDouble(), any());
    }
}
