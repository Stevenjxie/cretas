package com.cretas.aims.service.execution;

import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.learning.LearnedExpression;
import com.cretas.aims.service.ExpressionLearningService;
import com.cretas.aims.service.ToolRouterService;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.mockito.Mockito.*;

class DynamicSelectionSelfHealLearnTest {

    private ToolRouterService.SelectedTools singleTool(String tool) {
        ToolRouterService.SelectedTools.SelectedTool st =
            ToolRouterService.SelectedTools.SelectedTool.builder().toolName(tool).build();
        return ToolRouterService.SelectedTools.builder().tools(List.of(st)).build();
    }
    private ToolRouterService.ToolCandidate cand(String tool, double sim) {
        return ToolRouterService.ToolCandidate.builder().toolName(tool).similarity(sim).build();
    }
    private IntentExecuteResponse ok() {
        return IntentExecuteResponse.builder().status("SUCCESS").resultData(Map.of("x", 1)).build();
    }
    private AIIntentConfig owner(String tool) {
        AIIntentConfig i = new AIIntentConfig();
        i.setIntentCode("RESTAURANT_ORDER_STATISTICS"); i.setBusinessType("RESTAURANT"); i.setToolName(tool);
        return i;
    }
    private DynamicToolSelectionService svc(IntentConfigManagementService cfg, ExpressionLearningService els) {
        DynamicToolSelectionService s = new DynamicToolSelectionService(
            mock(ToolRouterService.class), mock(com.cretas.aims.ai.tool.ToolRegistry.class),
            new com.fasterxml.jackson.databind.ObjectMapper());
        ReflectionTestUtils.setField(s, "configService", cfg);
        ReflectionTestUtils.setField(s, "expressionLearningService", els);
        return s;
    }

    @Test
    void learns_whenSingleToolSuccessCompatibleHighSim() {
        IntentConfigManagementService cfg = mock(IntentConfigManagementService.class);
        ExpressionLearningService els = mock(ExpressionLearningService.class);
        when(cfg.resolveBusinessDomain("RES_1")).thenReturn("RESTAURANT");
        when(cfg.getAllIntents("RES_1")).thenReturn(List.of(owner("restaurant_order_type_mix_gold")));
        svc(cfg, els).maybeLearnFromDynamicSelection("外送生意占几成",
            singleTool("restaurant_order_type_mix_gold"),
            List.of(cand("restaurant_order_type_mix_gold", 0.72)), "RES_1", ok());
        verify(els).learnExpression(eq("RES_1"), eq("RESTAURANT_ORDER_STATISTICS"),
            eq("外送生意占几成"), eq(0.72), eq(LearnedExpression.SourceType.DYNAMIC_SELECTION));
    }

    @Test
    void doesNotLearn_whenLowSimilarity() {
        IntentConfigManagementService cfg = mock(IntentConfigManagementService.class);
        ExpressionLearningService els = mock(ExpressionLearningService.class);
        when(cfg.resolveBusinessDomain("RES_1")).thenReturn("RESTAURANT");
        when(cfg.getAllIntents("RES_1")).thenReturn(List.of(owner("restaurant_order_type_mix_gold")));
        svc(cfg, els).maybeLearnFromDynamicSelection("q",
            singleTool("restaurant_order_type_mix_gold"),
            List.of(cand("restaurant_order_type_mix_gold", 0.40)), "RES_1", ok());
        verifyNoInteractions(els);
    }

    @Test
    void doesNotLearn_whenNotSuccess() {
        IntentConfigManagementService cfg = mock(IntentConfigManagementService.class);
        ExpressionLearningService els = mock(ExpressionLearningService.class);
        IntentExecuteResponse failed = IntentExecuteResponse.builder().status("FAILED").build();
        svc(cfg, els).maybeLearnFromDynamicSelection("q", singleTool("t"),
            List.of(cand("t", 0.9)), "RES_1", failed);
        verifyNoInteractions(els);
    }

    @Test
    void doesNotLearn_whenMultiTool() {
        IntentConfigManagementService cfg = mock(IntentConfigManagementService.class);
        ExpressionLearningService els = mock(ExpressionLearningService.class);
        ToolRouterService.SelectedTools.SelectedTool a =
            ToolRouterService.SelectedTools.SelectedTool.builder().toolName("a").build();
        ToolRouterService.SelectedTools.SelectedTool b =
            ToolRouterService.SelectedTools.SelectedTool.builder().toolName("b").build();
        ToolRouterService.SelectedTools two = ToolRouterService.SelectedTools.builder()
            .tools(List.of(a, b)).build();
        svc(cfg, els).maybeLearnFromDynamicSelection("q", two,
            List.of(cand("a", 0.9), cand("b", 0.9)), "RES_1", ok());
        verifyNoInteractions(els);
    }

    @Test
    void doesNotLearn_whenIncompatibleBusinessType() {
        IntentConfigManagementService cfg = mock(IntentConfigManagementService.class);
        ExpressionLearningService els = mock(ExpressionLearningService.class);
        when(cfg.resolveBusinessDomain("RES_1")).thenReturn("RESTAURANT");
        AIIntentConfig factoryIntent = new AIIntentConfig();
        factoryIntent.setIntentCode("SKU_GROSS_MARGIN"); factoryIntent.setBusinessType("FACTORY");
        factoryIntent.setToolName("sku_tool");
        when(cfg.getAllIntents("RES_1")).thenReturn(List.of(factoryIntent));
        svc(cfg, els).maybeLearnFromDynamicSelection("q", singleTool("sku_tool"),
            List.of(cand("sku_tool", 0.9)), "RES_1", ok());
        verifyNoInteractions(els);  // FACTORY intent on RESTAURANT factory → not learned (poison-safe)
    }
}
