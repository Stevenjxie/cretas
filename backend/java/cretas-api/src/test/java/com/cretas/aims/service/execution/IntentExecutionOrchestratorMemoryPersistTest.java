package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
