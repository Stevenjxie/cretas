package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.conversation.ConversationContext;
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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

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

    /**
     * D1-fix regression: The normal execute() path calls updateConversationMemory directly
     * (not through persistConversationMemoryForExplicitIntent). This test verifies that
     * updateConversationMemory correctly extracts the DISH slot from a BESTSELLER response
     * and writes it via conversationMemoryService.updateEntitySlot — provided the session
     * row already exists (getOrCreateContext was called first, as the fix ensures).
     */
    @Test
    @DisplayName("D1-fix: normal execute path — BESTSELLER response → DISH slot written via updateConversationMemory")
    void normalPath_bestsellerResponse_writesDishSlot() {
        // Simulate the normal execute() path: getOrCreateContext is now called first (D1-fix),
        // then updateConversationMemory is invoked. We call updateConversationMemory directly
        // here (via reflection) to isolate the slot-writing logic independently of the full
        // execute() pipeline.
        IntentExecuteRequest req = IntentExecuteRequest.builder()
                .sessionId("sess-d1-fix")
                .userInput("哪道菜卖得最好")
                .build();
        IntentExecuteResponse resp = IntentExecuteResponse.builder()
                .status("SUCCESS")
                .message("本月畅销菜品")
                .resultData(Map.of(
                        "top_dish", Map.of(
                                "dish_id", 505,
                                "菜品", "招牌青花椒味",
                                "销量", 1200.0,
                                "销售额", 36000.0)))
                .build();
        AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode("RESTAURANT_BESTSELLER_QUERY")
                .build();
        IntentMatchResult matchResult = IntentMatchResult.builder()
                .bestMatch(intent)
                .build();

        // Call private updateConversationMemory directly (mirrors what execute() does after D1-fix)
        ReflectionTestUtils.invokeMethod(orchestrator, "updateConversationMemory",
                "sess-d1-fix", req, resp, matchResult, "RES_3101_009", 9L);

        ArgumentCaptor<EntitySlot> slotCaptor = ArgumentCaptor.forClass(EntitySlot.class);
        verify(memory).updateEntitySlot(
                eq("sess-d1-fix"), eq(EntitySlot.SlotType.DISH), slotCaptor.capture());
        EntitySlot slot = slotCaptor.getValue();
        assertThat(slot.getId()).isEqualTo("505");
        assertThat(slot.getName()).isEqualTo("招牌青花椒味");
        assertThat(slot.getDisplayValue()).isEqualTo("菜品 招牌青花椒味");
        // Also verify lastIntentCode was updated
        verify(memory).updateLastIntent("sess-d1-fix", "RESTAURANT_BESTSELLER_QUERY");
    }

    @Test
    @DisplayName("营业额双日比较写入绝对日期槽位")
    void revenueComparisonPersistsAbsoluteDateRanges() {
        AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode("RESTAURANT_OPS_SALES_SUMMARY").build();
        IntentExecuteRequest req = IntentExecuteRequest.builder()
                .sessionId("sess-date-persist")
                .userInput("昨天营业额比前天高还是低？")
                .build();
        IntentExecuteResponse resp = IntentExecuteResponse.builder()
                .status("COMPLETED")
                .message("比较完成")
                .build();

        orchestrator.persistConversationMemoryForExplicitIntent(
                "DEMO_REST", req, resp, intent, 9L);

        ArgumentCaptor<EntitySlot> slotCaptor = ArgumentCaptor.forClass(EntitySlot.class);
        verify(memory).updateEntitySlot(
                eq("sess-date-persist"), eq(EntitySlot.SlotType.TIME_RANGE), slotCaptor.capture());
        LocalDate anchor = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        assertThat(slotCaptor.getValue().getMetadata())
                .containsEntry("primaryStart", anchor.minusDays(1).toString())
                .containsEntry("primaryEnd", anchor.minusDays(1).toString())
                .containsEntry("comparisonStart", anchor.minusDays(2).toString())
                .containsEntry("comparisonEnd", anchor.minusDays(2).toString())
                .containsEntry("anchorDate", anchor.toString());
    }

    @Test
    @DisplayName("日期追问只从同一 session 恢复两个绝对日期并保留既有上下文")
    void comparisonFollowupHydratesSameSessionAbsoluteDates() {
        EntitySlot slot = EntitySlot.builder()
                .type(EntitySlot.SlotType.TIME_RANGE)
                .metadata(Map.of(
                        "primaryStart", "2026-07-20",
                        "primaryEnd", "2026-07-20",
                        "comparisonStart", "2026-07-19",
                        "comparisonEnd", "2026-07-19",
                        "anchorDate", "2026-07-21"))
                .build();
        when(memory.getContext("sess-date-followup")).thenReturn(
                ConversationContext.builder()
                        .sessionId("sess-date-followup")
                        .factoryId("DEMO_REST")
                        .userId(9L)
                        .build());
        when(memory.getEntitySlot("sess-date-followup", EntitySlot.SlotType.TIME_RANGE))
                .thenReturn(slot);
        IntentExecuteRequest req = IntentExecuteRequest.builder()
                .sessionId("sess-date-followup")
                .userInput("那毛利呢？请沿用刚才比较的两个日期。")
                .context(Map.of("store_name", "鲜行者打浦桥日月光店"))
                .build();

        orchestrator.hydrateRestaurantComparisonContext("DEMO_REST", 9L, req);

        assertThat(req.getContext())
                .containsEntry("store_name", "鲜行者打浦桥日月光店")
                .containsEntry("startDate", "2026-07-20")
                .containsEntry("endDate", "2026-07-20")
                .containsEntry("comparisonStartDate", "2026-07-19")
                .containsEntry("comparisonEndDate", "2026-07-19")
                .containsEntry("timeAnchorDate", "2026-07-21");
        verify(memory).getEntitySlot("sess-date-followup", EntitySlot.SlotType.TIME_RANGE);
    }
    @Test
    @DisplayName("其他用户或工厂的 session 必须 fail-closed 且不得读取日期槽位")
    void foreignSessionIsRejectedBeforeSlotLookup() {
        when(memory.getContext("foreign-session")).thenReturn(
                ConversationContext.builder()
                        .sessionId("foreign-session")
                        .factoryId("RES_3101_009")
                        .userId(99L)
                        .build());
        IntentExecuteRequest req = IntentExecuteRequest.builder()
                .sessionId("foreign-session")
                .userInput("那毛利呢？请沿用刚才比较的两个日期。")
                .build();

        orchestrator.hydrateRestaurantComparisonContext("DEMO_REST", 9L, req);

        assertThat(req.getSessionId()).isNull();
        assertThat(req.getContext()).isNull();
        verify(memory, never()).getEntitySlot("foreign-session", EntitySlot.SlotType.TIME_RANGE);
    }

    @Test
    @DisplayName("本轮包含显式新日期时不得混入旧 comparison 日期")
    void explicitTimeOverrideDoesNotHydrateOldComparison() {
        IntentExecuteRequest req = IntentExecuteRequest.builder()
                .sessionId("sess-date-override")
                .userInput("那毛利呢？今天重新看，但不要沿用刚才比较的两个日期。")
                .build();

        orchestrator.hydrateRestaurantComparisonContext("DEMO_REST", 9L, req);

        assertThat(req.getContext()).isNull();
        verifyNoInteractions(memory);
    }

    @Test
    @DisplayName("重叠或反向日期集合必须整组拒绝")
    void invalidComparisonDateSetIsRejectedAtomically() {
        when(memory.getContext("sess-invalid-date")).thenReturn(
                ConversationContext.builder()
                        .sessionId("sess-invalid-date")
                        .factoryId("DEMO_REST")
                        .userId(9L)
                        .build());
        when(memory.getEntitySlot("sess-invalid-date", EntitySlot.SlotType.TIME_RANGE))
                .thenReturn(EntitySlot.builder()
                        .type(EntitySlot.SlotType.TIME_RANGE)
                        .metadata(Map.of(
                                "primaryStart", "2026-07-20",
                                "primaryEnd", "2026-07-19",
                                "comparisonStart", "2026-07-19",
                                "comparisonEnd", "2026-07-20",
                                "anchorDate", "2026-07-21"))
                        .build());
        IntentExecuteRequest req = IntentExecuteRequest.builder()
                .sessionId("sess-invalid-date")
                .userInput("那毛利呢？请沿用刚才比较的两个日期。")
                .build();

        orchestrator.hydrateRestaurantComparisonContext("DEMO_REST", 9L, req);

        assertThat(req.getContext()).isNull();
        assertThat(req.getSessionId()).isEqualTo("sess-invalid-date");
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
