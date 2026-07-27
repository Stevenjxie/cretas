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
import static org.mockito.Mockito.*;

@DisplayName("IntentExecutionOrchestrator STORE reference clarification")
class IntentExecutionOrchestratorStoreClarificationTest {

    private IntentExecutionOrchestrator orchestrator;
    private AIIntentService aiIntentService;

    @BeforeEach
    void setUp() {
        aiIntentService = mock(AIIntentService.class);
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
