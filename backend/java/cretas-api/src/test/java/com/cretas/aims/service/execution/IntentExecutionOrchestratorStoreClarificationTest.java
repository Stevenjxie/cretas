package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.ai.PreprocessedQuery;
import com.cretas.aims.dto.conversation.ConversationMessage;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("IntentExecutionOrchestrator STORE reference clarification")
class IntentExecutionOrchestratorStoreClarificationTest {

    private IntentExecutionOrchestrator orchestrator;
    private AIIntentService aiIntentService;
    private ConversationMemoryService conversationMemoryService;

    @BeforeEach
    void setUp() {
        aiIntentService = mock(AIIntentService.class);
        conversationMemoryService = mock(ConversationMemoryService.class);
        orchestrator = new IntentExecutionOrchestrator(
                aiIntentService,
                mock(IntentSemanticsParser.class),
                mock(SemanticCacheService.class),
                mock(RuleEngineService.class),
                mock(ConversationService.class),
                conversationMemoryService,
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
    @DisplayName("verified time clarification carries the parent ranking question into execution")
    void timeClarificationCarriesParentQuestionIntoExecution() {
        when(conversationMemoryService.getRecentMessages("sid-store-time", 8))
                .thenReturn(List.of(
                        ConversationMessage.user("哪家店业绩最好"),
                        ConversationMessage.assistant(
                                "你想看哪个时间范围？请选择本月、上个月、最近7天或最近30天。")));
        IntentMatchResult inherited = IntentMatchResult.builder()
                .bestMatch(storeRankIntent())
                .matchMethod(IntentMatchResult.MatchMethod.CONTINUATION_INHERIT)
                .preprocessedQuery(PreprocessedQuery.builder()
                        .originalInput("最近30天")
                        .finalQuery("最近30天门店营收排行")
                        .build())
                .build();

        String executionInput = orchestrator.resolveSessionAwareRestaurantContinuationInput(
                request("最近30天", "sid-store-time"), inherited);

        assertThat(executionInput).isEqualTo("哪家店业绩最好，最近30天");
    }

    @Test
    @DisplayName("non-clarification continuation uses the safe augmented query")
    void nonClarificationContinuationUsesAugmentedQuery() {
        when(conversationMemoryService.getRecentMessages("sid-period-change", 8))
                .thenReturn(List.of(
                        ConversationMessage.user("哪家店业绩最好"),
                        ConversationMessage.assistant(
                                "你想看哪个时间范围？请选择本月、上个月、最近7天或最近30天。"),
                        ConversationMessage.user("本月门店营收排行"),
                        ConversationMessage.assistant("门店营收排行结果")));
        IntentMatchResult inherited = IntentMatchResult.builder()
                .bestMatch(storeRankIntent())
                .matchMethod(IntentMatchResult.MatchMethod.CONTINUATION_INHERIT)
                .preprocessedQuery(PreprocessedQuery.builder()
                        .originalInput("上个月")
                        .finalQuery("上个月门店营收排行")
                        .build())
                .build();

        String executionInput = orchestrator.resolveSessionAwareRestaurantContinuationInput(
                request("上个月", "sid-period-change"), inherited);

        assertThat(executionInput).isEqualTo("上个月门店营收排行");
    }

    @Test
    @DisplayName("bare restaurant time reply bridges only a verified READ continuation")
    void bareRestaurantTimeBridgesVerifiedContinuation() {
        IntentMatchResult inherited = IntentMatchResult.builder()
                .bestMatch(storeRankIntent())
                .confidence(0.97)
                .matchMethod(IntentMatchResult.MatchMethod.CONTINUATION_INHERIT)
                .build();
        when(aiIntentService.recognizeIntentWithConfidence(
                eq("最近30天"), eq("DEMO_REST"), eq(3),
                eq(1632L), eq("factory_super_admin"), eq("sid-store-time")))
                .thenReturn(inherited);

        IntentMatchResult result = orchestrator.recognizeSessionAwareRestaurantContinuation(
                "DEMO_REST",
                request("最近30天", "sid-store-time"),
                1632L,
                "factory_super_admin");

        assertThat(result).isSameAs(inherited);
    }

    @Test
    @DisplayName("session bridge cannot steal a full question or an unverified match")
    void sessionBridgeRejectsStandaloneAndUnverifiedMatches() {
        assertThat(orchestrator.recognizeSessionAwareRestaurantContinuation(
                "DEMO_REST",
                request("最近30天哪家店业绩最好", "sid-full-question"),
                1632L,
                "factory_super_admin")).isNull();
        verifyNoInteractions(aiIntentService);

        when(aiIntentService.recognizeIntentWithConfidence(
                eq("最近30天"), eq("DEMO_REST"), eq(3),
                eq(1632L), eq("factory_super_admin"), eq("sid-unverified")))
                .thenReturn(IntentMatchResult.builder()
                        .bestMatch(storeRankIntent())
                        .confidence(0.85)
                        .matchMethod(IntentMatchResult.MatchMethod.SEMANTIC)
                        .build());

        assertThat(orchestrator.recognizeSessionAwareRestaurantContinuation(
                "DEMO_REST",
                request("最近30天", "sid-unverified"),
                1632L,
                "factory_super_admin")).isNull();
    }

    @Test
    @DisplayName("store pronoun without resolved STORE requires clarification")
    void unresolvedStorePronounRequiresClarification() {
        boolean required = orchestrator.requiresStoreReferenceClarification(
                request("那家店的客单价", "sid-clarify"),
                IntentMatchResult.builder()
                        .preprocessedQuery(PreprocessedQuery.builder()
                                .originalInput("那家店的客单价")
                                .build())
                        .build(),
                storeRankIntent());

        assertThat(required).isTrue();
        IntentExecuteResponse response = orchestrator.buildStoreReferenceClarificationResponse(
                request("那家店的客单价", "sid-clarify"));
        assertThat(response.getStatus()).isEqualTo("NEED_CLARIFICATION");
        assertThat(response.getMessage()).isEqualTo("请问您指的是哪家店？");
        assertThat(response.getFormattedText()).isEqualTo("请问您指的是哪家店？");
        assertThat(response.getSessionId()).isEqualTo("sid-clarify");
    }

    @Test
    @DisplayName("resolved STORE reference does not require clarification")
    void resolvedStoreDoesNotClarify() {
        PreprocessedQuery pq = PreprocessedQuery.builder()
                .resolvedReferences(Map.of(
                        "那家店",
                        PreprocessedQuery.ResolvedReference.of("store", "101", "人民广场店", "那家店")))
                .build();

        boolean required = orchestrator.requiresStoreReferenceClarification(
                request("那家店的客单价", "sid-ok"),
                IntentMatchResult.builder().preprocessedQuery(pq).build(),
                storeRankIntent());

        assertThat(required).isFalse();
    }

    @Test
    @DisplayName("first-turn ranking question does not require clarification")
    void firstTurnRankingQuestionDoesNotClarify() {
        boolean required = orchestrator.requiresStoreReferenceClarification(
                request("哪家店业绩最好", "sid-first"),
                IntentMatchResult.builder().build(),
                storeRankIntent());

        assertThat(required).isFalse();
    }

    @Test
    @DisplayName("store reference follow-up bypasses early phrase shortcut")
    void storeReferenceFollowUpBypassesEarlyPhraseShortcut() {
        assertThat(orchestrator.shouldBypassEarlyPhraseShortcutForStoreReference("那家店的客单价呢")).isTrue();
        assertThat(orchestrator.shouldBypassEarlyPhraseShortcutForStoreReference("这家的客单价呢")).isTrue();
        assertThat(orchestrator.shouldBypassEarlyPhraseShortcutForStoreReference("哪家店业绩最好")).isFalse();
        assertThat(orchestrator.shouldBypassEarlyPhraseShortcutForStoreReference("客单价排行")).isFalse();
    }

    private static IntentExecuteRequest request(String input, String sessionId) {
        return IntentExecuteRequest.builder()
                .userInput(input)
                .sessionId(sessionId)
                .build();
    }

    private static AIIntentConfig storeRankIntent() {
        return AIIntentConfig.builder()
                .intentCode("RESTAURANT_STORE_REVENUE_RANK")
                .intentName("门店营收排行")
                .build();
    }
}
