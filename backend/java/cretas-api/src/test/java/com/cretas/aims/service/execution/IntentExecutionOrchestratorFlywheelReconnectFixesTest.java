package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.impl.restaurant.TieredIntentDelegate;
import com.cretas.aims.client.GoldFinanceClient;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.config.IntentKnowledgeBase.QuestionType;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 餐饮 AI 飞轮回接 Wave1 卡1 — 回归测试: 重复委托 / veto 误判 / READ 模式 mode 透传。
 *
 * <p>三个缺陷分别对应:
 * <ol>
 *   <li>{@code TieredIntentDelegate.ATTEMPTED_CONTEXT_KEY} 曾只有读取点、无生产写入点
 *       (被 commit {@code acd1a5bb5} 删除) — 同一请求经不同兜底路径重复调用
 *       {@code tryRestaurantTieredDelegate} 会对 Python tiered 路由重复委托 (含 T3
 *       REVIEW 档 LLM, 纯浪费)。</li>
 *   <li>{@code hasExplicitReadVeto} 纯 contains 匹配 16 个否定词, 把维度级否定
 *       (如 "不看堂食只看外卖营收") 误判成整条 veto。</li>
 *   <li>{@code handleEarlyQuestionTypeDetection} 的短语路由分支丢了 mode/previewOnly
 *       透传, 导致 READ 模式下写意图绕过 {@code READ_MODE_WRITE_BLOCKED} 拦截。</li>
 * </ol>
 */
@DisplayName("IntentExecutionOrchestrator — 飞轮回接卡1回归 (重复委托/veto对象/READ透传)")
class IntentExecutionOrchestratorFlywheelReconnectFixesTest {

    private IntentExecutionOrchestrator orchestrator;
    private AIIntentService aiIntentService;
    private IntentKnowledgeBase knowledgeBase;
    private ToolDispatchService dispatchService;
    private BusinessTypeGate businessTypeGate;

    @BeforeEach
    void setUp() {
        aiIntentService = mock(AIIntentService.class);
        knowledgeBase = mock(IntentKnowledgeBase.class);
        dispatchService = mock(ToolDispatchService.class);
        orchestrator = new IntentExecutionOrchestrator(
                aiIntentService,
                mock(IntentSemanticsParser.class),
                mock(SemanticCacheService.class),
                mock(RuleEngineService.class),
                mock(ConversationService.class),
                mock(ConversationMemoryService.class),
                new ObjectMapper(),
                mock(DashScopeClient.class),
                mock(DashScopeConfig.class),
                knowledgeBase,
                mock(AIAnalysisResultRepository.class),
                mock(ToolRegistry.class),
                mock(AnalysisRouterService.class),
                mock(ComplexityRouter.class),
                mock(AgentOrchestrator.class),
                mock(AgenticRAGRouterService.class),
                mock(ResultValidatorService.class),
                dispatchService,
                mock(DynamicToolSelectionService.class),
                mock(QueryPreprocessorService.class));
        businessTypeGate = mock(BusinessTypeGate.class);
        when(businessTypeGate.check(any(), any())).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(orchestrator, "businessTypeGate", businessTypeGate);
        ReflectionTestUtils.setField(orchestrator, "writeGuardService", new WriteGuardService());
    }

    // ==================== 1. 重复委托去重 (R16 ATTEMPTED_CONTEXT_KEY 写入) ====================

    @Test
    @DisplayName("同一 request 经两个不同调用点走 tryRestaurantTieredDelegate, Python 只被问一次")
    void sameRequestDelegatesToPythonOnlyOnce() throws Exception {
        GoldFinanceClient gold = mock(GoldFinanceClient.class);
        TieredIntentDelegate delegate = new TieredIntentDelegate();
        Field goldField = TieredIntentDelegate.class.getDeclaredField("gold");
        goldField.setAccessible(true);
        goldField.set(delegate, gold);
        ReflectionTestUtils.setField(orchestrator, "tieredIntentDelegate", delegate);

        when(gold.fetchTieredIntentAnswer(eq("DEMO_REST"), eq("米饭的销量是多少"), anyString()))
                .thenReturn(Map.of("delegate", false));

        IntentExecuteRequest request = IntentExecuteRequest.builder()
                .userInput("米饭的销量是多少")
                .build();

        // 第一次: 模拟反转入口 (如 handleEarlyQuestionTypeDetection 的 earlyTieredDelegated)
        // 首次尝试委托。
        IntentExecuteResponse first = ReflectionTestUtils.invokeMethod(
                orchestrator, "tryRestaurantTieredDelegate", "DEMO_REST", "米饭的销量是多少", request);
        // 第二次: 模拟同一请求在兜底路径 (noToolResponseWithRestaurantFallback /
        // executeAnalysisFlow / executeRestaurantOwnerActionChat) 再次调用同一 helper —
        // R16 修复前这里会对 Python 发起第二次委托请求。
        IntentExecuteResponse second = ReflectionTestUtils.invokeMethod(
                orchestrator, "tryRestaurantTieredDelegate", "DEMO_REST", "米饭的销量是多少", request);

        assertThat(first).isNull();
        assertThat(second).isNull();
        verify(gold, times(1))
                .fetchTieredIntentAnswer(eq("DEMO_REST"), eq("米饭的销量是多少"), anyString());
        assertThat(request.getContext())
                .containsEntry(TieredIntentDelegate.ATTEMPTED_CONTEXT_KEY, Boolean.TRUE);
    }

    // ==================== 2. hasExplicitReadVeto 否定对象判断 ====================

    @Test
    @DisplayName("维度级否定「不看堂食只看外卖营收」放行语义规划(不 veto); 全量否定「别看订单」仍然 veto")
    void dimensionalNegationDoesNotVetoButWholeQueryNegationStillDoes() {
        Boolean dimensionalNegationVetoed = ReflectionTestUtils.invokeMethod(
                orchestrator, "hasExplicitReadVeto", "不看堂食只看外卖营收");
        Boolean anotherDimensionalCase = ReflectionTestUtils.invokeMethod(
                orchestrator, "hasExplicitReadVeto", "不查库存只查销量");
        Boolean wholeQueryVetoed = ReflectionTestUtils.invokeMethod(
                orchestrator, "hasExplicitReadVeto", "别看订单");
        Boolean anotherWholeQueryVetoed = ReflectionTestUtils.invokeMethod(
                orchestrator, "hasExplicitReadVeto", "不想看这个");

        assertThat(dimensionalNegationVetoed).as("维度级否定应放行语义规划, 不算 veto").isFalse();
        assertThat(anotherDimensionalCase).as("维度级否定应放行语义规划, 不算 veto").isFalse();
        assertThat(wholeQueryVetoed).as("否定整条查询仍应 veto").isTrue();
        assertThat(anotherWholeQueryVetoed).as("否定整条查询仍应 veto").isTrue();
    }

    // ==================== 3. READ 模式短语路由 mode/previewOnly 透传 ====================

    @Test
    @DisplayName("READ 模式短语路由命中写意图 → 返回 READ_MODE_WRITE_BLOCKED, 工具零执行")
    void readModePhraseRouteReturnsReadModeWriteBlocked() {
        String factoryId = "F006";
        String userInput = "帮我登记新一批原料入库";
        String matchedIntentCode = "MATERIAL_INBOUND_CREATE";

        when(knowledgeBase.detectQuestionType(userInput)).thenReturn(QuestionType.CONVERSATIONAL);
        when(knowledgeBase.matchPhrase(userInput, "FACTORY"))
                .thenReturn(Optional.of(matchedIntentCode));

        AIIntentConfig writeIntent = new AIIntentConfig();
        writeIntent.setIntentCode(matchedIntentCode);
        writeIntent.setIntentName("原料入库");
        writeIntent.setIntentCategory("DATA_OP");
        writeIntent.setSensitivityLevel("HIGH");
        writeIntent.setToolName("material_inbound_create");
        when(aiIntentService.getIntentByCode(factoryId, matchedIntentCode))
                .thenReturn(Optional.of(writeIntent));
        when(aiIntentService.hasPermission(matchedIntentCode, "factory_super_admin")).thenReturn(true);

        IntentExecuteRequest request = IntentExecuteRequest.builder()
                .userInput(userInput)
                .mode("READ")
                .build();

        IntentExecuteResponse response = ReflectionTestUtils.invokeMethod(
                orchestrator,
                "handleEarlyQuestionTypeDetection",
                factoryId,
                userInput,
                request,
                22L,
                "factory_super_admin");

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("READ_MODE_WRITE_BLOCKED");
        assertThat(response.getAiMode()).isEqualTo("WRITE");
        verify(dispatchService, never())
                .executeWithTool(any(), any(), any(), any(), any(), any(), any());
    }
}
