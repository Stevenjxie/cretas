package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.ai.PreprocessedQuery;
import com.cretas.aims.dto.intent.IntentMatchResult;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("IntentExecutionOrchestrator DISH reference clarification")
class IntentExecutionOrchestratorDishClarificationTest {

    private IntentExecutionOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new IntentExecutionOrchestrator(
                mock(AIIntentService.class),
                mock(IntentSemanticsParser.class),
                mock(SemanticCacheService.class),
                mock(RuleEngineService.class),
                mock(ConversationService.class),
                mock(ConversationMemoryService.class),
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
    @DisplayName("dish pronoun without resolved DISH requires clarification")
    void unresolvedDishPronounRequiresClarification() {
        boolean required = orchestrator.requiresDishReferenceClarification(
                request("那道菜的趋势", "sid-clarify"),
                IntentMatchResult.builder()
                        .preprocessedQuery(PreprocessedQuery.builder()
                                .originalInput("那道菜的趋势")
                                .build())
                        .build(),
                bestsellerIntent());

        assertThat(required).isTrue();
        IntentExecuteResponse response = orchestrator.buildDishReferenceClarificationResponse(
                request("那道菜的趋势", "sid-clarify"));
        assertThat(response.getStatus()).isEqualTo("NEED_CLARIFICATION");
        assertThat(response.getMessage()).isEqualTo("请问您指的是哪道菜？");
        assertThat(response.getFormattedText()).isEqualTo("请问您指的是哪道菜？");
        assertThat(response.getSessionId()).isEqualTo("sid-clarify");
    }

    @Test
    @DisplayName("resolved DISH reference does not require clarification")
    void resolvedDishDoesNotClarify() {
        PreprocessedQuery pq = PreprocessedQuery.builder()
                .resolvedReferences(Map.of(
                        "那道菜",
                        PreprocessedQuery.ResolvedReference.of("dish", "201", "叮咚卤猪蹄", "那道菜")))
                .build();

        boolean required = orchestrator.requiresDishReferenceClarification(
                request("那道菜的趋势", "sid-ok"),
                IntentMatchResult.builder().preprocessedQuery(pq).build(),
                bestsellerIntent());

        assertThat(required).isFalse();
    }

    @Test
    @DisplayName("first-turn bestseller question does not require clarification")
    void firstTurnBestsellerQuestionDoesNotClarify() {
        boolean required = orchestrator.requiresDishReferenceClarification(
                request("哪道菜卖得最好", "sid-first"),
                IntentMatchResult.builder().build(),
                bestsellerIntent());

        assertThat(required).isFalse();
    }

    @Test
    @DisplayName("dish reference follow-up bypasses early phrase shortcut")
    void dishReferenceFollowUpBypassesEarlyPhraseShortcut() {
        assertThat(orchestrator.shouldBypassEarlyPhraseShortcutForDishReference("那道菜的趋势呢")).isTrue();
        assertThat(orchestrator.shouldBypassEarlyPhraseShortcutForDishReference("这道菜的价格")).isTrue();
        assertThat(orchestrator.shouldBypassEarlyPhraseShortcutForDishReference("它的销售额")).isTrue();
        assertThat(orchestrator.shouldBypassEarlyPhraseShortcutForDishReference("哪道菜卖得最好")).isFalse();
        assertThat(orchestrator.shouldBypassEarlyPhraseShortcutForDishReference("畅销菜品排行")).isFalse();
    }

    @Test
    @DisplayName("non-bestseller intent does not trigger dish clarification")
    void nonBestsellerIntentDoesNotTriggerDishClarification() {
        boolean required = orchestrator.requiresDishReferenceClarification(
                request("那道菜的趋势", "sid-other"),
                IntentMatchResult.builder().build(),
                AIIntentConfig.builder()
                        .intentCode("RESTAURANT_STORE_REVENUE_RANK")
                        .intentName("门店营收排行")
                        .build());

        assertThat(required).isFalse();
    }

    private static IntentExecuteRequest request(String input, String sessionId) {
        return IntentExecuteRequest.builder()
                .userInput(input)
                .sessionId(sessionId)
                .build();
    }

    private static AIIntentConfig bestsellerIntent() {
        return AIIntentConfig.builder()
                .intentCode("RESTAURANT_BESTSELLER_QUERY")
                .intentName("畅销菜品查询")
                .build();
    }
}
