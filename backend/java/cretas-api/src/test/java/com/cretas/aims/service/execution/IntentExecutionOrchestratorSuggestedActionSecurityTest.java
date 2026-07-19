package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.conversation.ConversationSession;
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
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntentExecutionOrchestratorSuggestedActionSecurityTest {

    private static final String FACTORY_ID = "F001";
    private static final String INTENT_CODE = "MATERIAL_BATCH_QUERY";

    private IntentExecutionOrchestrator orchestrator;
    private ConversationService conversationService;
    private IntentConfigManagementService configService;

    @BeforeEach
    void setUp() {
        conversationService = mock(ConversationService.class);
        orchestrator = new IntentExecutionOrchestrator(
                mock(AIIntentService.class),
                mock(IntentSemanticsParser.class),
                mock(SemanticCacheService.class),
                mock(RuleEngineService.class),
                conversationService,
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

        configService = mock(IntentConfigManagementService.class);
        ReflectionTestUtils.setField(orchestrator, "configService", configService);
    }

    @Test
    void clarificationCandidateKeepsIntentCodeWithoutForceExecute() {
        when(configService.resolveBusinessDomain(FACTORY_ID)).thenReturn("FACTORY");
        AIIntentConfig config = new AIIntentConfig();
        config.setIntentCode(INTENT_CODE);
        config.setBusinessType("FACTORY");
        when(configService.getIntentByCode(FACTORY_ID, INTENT_CODE)).thenReturn(Optional.of(config));

        IntentMatchResult matchResult = IntentMatchResult.builder()
                .topCandidates(List.of(IntentMatchResult.CandidateIntent.builder()
                        .intentCode(INTENT_CODE)
                        .intentName("Material query")
                        .confidence(0.70)
                        .build()))
                .build();

        @SuppressWarnings("unchecked")
        List<IntentExecuteResponse.SuggestedAction> actions =
                (List<IntentExecuteResponse.SuggestedAction>) ReflectionTestUtils.invokeMethod(
                        orchestrator, "buildCandidateActions", matchResult, FACTORY_ID);

        IntentExecuteResponse.SuggestedAction selectIntent = actions.stream()
                .filter(action -> "SELECT_INTENT".equals(action.getActionCode()))
                .findFirst()
                .orElseThrow();
        assertThat(selectIntent.getParameters())
                .containsEntry("intentCode", INTENT_CODE)
                .doesNotContainKey("forceExecute");
    }

    @Test
    void conversationCandidateKeepsIntentAndSessionWithoutForceExecute() {
        ConversationService.ConversationResponse conversationResponse =
                ConversationService.ConversationResponse.builder()
                        .sessionId("session-1")
                        .status(ConversationSession.SessionStatus.ACTIVE)
                        .message("Choose an intent")
                        .candidates(List.of(ConversationService.CandidateInfo.builder()
                                .intentCode(INTENT_CODE)
                                .intentName("Material query")
                                .confidence(0.70)
                                .build()))
                        .build();
        when(conversationService.continueConversation(FACTORY_ID, 1L, "session-1", "choose"))
                .thenReturn(conversationResponse);

        IntentExecuteRequest request = IntentExecuteRequest.builder()
                .sessionId("session-1")
                .userInput("choose")
                .build();
        IntentExecuteResponse response = ReflectionTestUtils.invokeMethod(
                orchestrator, "handleConversationContinuation", FACTORY_ID, request, 1L, "ADMIN");

        assertThat(response).isNotNull();
        assertThat(response.getSuggestedActions()).hasSize(1);
        assertThat(response.getSuggestedActions().get(0).getParameters())
                .containsEntry("intentCode", INTENT_CODE)
                .containsEntry("sessionId", "session-1")
                .doesNotContainKey("forceExecute");
    }
}
