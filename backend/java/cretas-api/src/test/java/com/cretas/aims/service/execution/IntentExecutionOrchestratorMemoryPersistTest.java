package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.conversation.EntitySlot;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.AgentOrchestrator;
import com.cretas.aims.service.AgenticRAGRouterService;
import com.cretas.aims.service.AnalysisRouterService;
import com.cretas.aims.service.ComplexityRouter;
import com.cretas.aims.service.ConversationMemoryService;
import com.cretas.aims.service.ConversationService;
import com.cretas.aims.service.IntentSemanticsParser;
import com.cretas.aims.service.QueryPreprocessorService;
import com.cretas.aims.service.ResultValidatorService;
import com.cretas.aims.service.RuleEngineService;
import com.cretas.aims.service.SemanticCacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@DisplayName("IntentExecutionOrchestrator — X1 explicit-intent memory persistence")
class IntentExecutionOrchestratorMemoryPersistTest {

    private IntentExecutionOrchestrator orchestrator;
    private ConversationMemoryService memory;

    @BeforeEach
    void setUp() {
        memory = mock(ConversationMemoryService.class);
        orchestrator = new IntentExecutionOrchestrator(
                mock(AIIntentService.class),
                mock(IntentSemanticsParser.class),
                mock(SemanticCacheService.class),
                mock(RuleEngineService.class),
                mock(ConversationService.class),
                memory,
                new ObjectMapper(),
                mock(DashScopeClient.class),
                mock(DashScopeConfig.class),
                mock(IntentKnowledgeBase.class),
                mock(AIAnalysisResultRepository.class),
                mock(ToolRegistry.class),
                mock(AnalysisRouterService.class),
                mock(ComplexityRouter.class),
                mock(AgentOrchestrator.class),
                mock(AgenticRAGRouterService.class),
                mock(ResultValidatorService.class),
                mock(ToolDispatchService.class),
                mock(DynamicToolSelectionService.class),
                mock(QueryPreprocessorService.class));
    }

    @Test
    @DisplayName("带 session 的显式意图执行 → 建行 (getOrCreateContext) + 写 lastIntentCode")
    void withSession_persistsLastIntent() {
        AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode("RESTAURANT_REVENUE_TREND").build();
        IntentExecuteRequest req = IntentExecuteRequest.builder()
                .sessionId("sess-mt-1").userInput("营收趋势").build();
        IntentExecuteResponse resp = IntentExecuteResponse.builder()
                .status("COMPLETED").build();

        orchestrator.persistConversationMemoryForExplicitIntent(
                "RES_3101_009", req, resp, intent, 9L);

        verify(memory).getOrCreateContext("RES_3101_009", 9L, "sess-mt-1");
        verify(memory).updateLastIntent("sess-mt-1", "RESTAURANT_REVENUE_TREND");
        verify(memory, times(2)).addMessage(eq("sess-mt-1"), any());
        verifyNoMoreInteractions(memory);
    }

    @Test
    @DisplayName("显式门店排行结果 top_store → 写 STORE 槽位")
    void withTopStoreResult_persistsStoreEntitySlot() {
        AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode("RESTAURANT_STORE_REVENUE_RANK").build();
        IntentExecuteRequest req = IntentExecuteRequest.builder()
                .sessionId("sess-store").userInput("哪家店业绩最好").build();
        IntentExecuteResponse resp = IntentExecuteResponse.builder()
                .status("COMPLETED")
                .resultData(Map.of(
                        "top_store", Map.of(
                                "store_id", 101,
                                "门店", "人民广场店",
                                "营收", 2000.0,
                                "单数", 20)))
                .build();

        orchestrator.persistConversationMemoryForExplicitIntent(
                "RES_3101_009", req, resp, intent, 9L);

        ArgumentCaptor<EntitySlot> slotCaptor = ArgumentCaptor.forClass(EntitySlot.class);
        verify(memory).updateEntitySlot(
                eq("sess-store"), eq(EntitySlot.SlotType.STORE), slotCaptor.capture());
        EntitySlot slot = slotCaptor.getValue();
        assertThat(slot.getId()).isEqualTo("101");
        assertThat(slot.getName()).isEqualTo("人民广场店");
        assertThat(slot.getDisplayValue()).isEqualTo("门店 人民广场店");
    }

    @Test
    @DisplayName("显式畅销菜品结果 top_dish → 写 DISH 槽位")
    void withTopDishResult_persistsDishEntitySlot() {
        AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode("RESTAURANT_BESTSELLER_QUERY").build();
        IntentExecuteRequest req = IntentExecuteRequest.builder()
                .sessionId("sess-dish").userInput("哪道菜卖得最好").build();
        IntentExecuteResponse resp = IntentExecuteResponse.builder()
                .status("COMPLETED")
                .resultData(Map.of(
                        "top_dish", Map.of(
                                "dish_id", 201,
                                "菜品", "叮咚卤猪蹄",
                                "销量", 1500.0)))
                .build();

        orchestrator.persistConversationMemoryForExplicitIntent(
                "RES_3101_009", req, resp, intent, 9L);

        ArgumentCaptor<EntitySlot> slotCaptor = ArgumentCaptor.forClass(EntitySlot.class);
        verify(memory).updateEntitySlot(
                eq("sess-dish"), eq(EntitySlot.SlotType.DISH), slotCaptor.capture());
        EntitySlot slot = slotCaptor.getValue();
        assertThat(slot.getId()).isEqualTo("201");
        assertThat(slot.getName()).isEqualTo("叮咚卤猪蹄");
        assertThat(slot.getDisplayValue()).isEqualTo("菜品 叮咚卤猪蹄");
    }

    @Test
    @DisplayName("无 session 的显式意图执行 → 完全不碰对话记忆 (parity/golden 安全)")
    void withoutSession_noMemoryInteraction() {
        AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode("RESTAURANT_REVENUE_TREND").build();
        IntentExecuteRequest req = IntentExecuteRequest.builder()
                .userInput("营收趋势").build();
        IntentExecuteResponse resp = IntentExecuteResponse.builder()
                .status("COMPLETED").build();

        orchestrator.persistConversationMemoryForExplicitIntent(
                "RES_3101_009", req, resp, intent, 9L);

        verifyNoInteractions(memory);
    }
}
