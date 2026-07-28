package com.cretas.aims.service.execution;

import com.cretas.aims.ai.capability.FactoryCapabilityPackRegistry;
import com.cretas.aims.ai.capability.FactoryCapabilityPackRoutingPolicy;
import com.cretas.aims.ai.capability.FactoryCapabilityPackSelector;
import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.gateway.AuthenticatedToolPrincipalFactory;
import com.cretas.aims.ai.tool.gateway.ToolExecutionGateway;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.cache.SemanticCacheHit;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.repository.FactoryRepository;
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
import com.cretas.aims.service.restaurant.RestaurantGrossMarginChatRouteSelector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IntentExecutionOrchestratorFactoryPackRouteTest {

    @Test
    void packExternalQueryStopsBeforeRestaurantContinuationAndGeneralAnalysis() {
        Harness harness = new Harness();

        IntentExecuteResponse response = harness.orchestrator.execute(
                "F001",
                IntentExecuteRequest.builder()
                        .userInput("审批付款")
                        .sessionId("continued-session")
                        .build(),
                7L,
                "operator");

        assertThat(response.getStatus()).isEqualTo("FACTORY_PACK_NO_MATCH");
        verify(harness.factoryRepository, times(1)).findById("F001");
        verifyNoInteractions(
                harness.restaurantSelector,
                harness.conversationService,
                harness.analysisRouterService,
                harness.agentOrchestrator,
                harness.ragRouterService,
                harness.dashScopeClient,
                harness.ownerToolGateway,
                harness.principalFactory,
                harness.aiIntentService);
    }

    @Test
    void explicitMutationReturnsDeclaredWorkflowGuidanceWithoutExecution() {
        Harness harness = new Harness();
        AIIntentConfig intent = intent(
                "PRODUCTION_REPORT", "production_report_submit", "生产报工");
        when(harness.aiIntentService.getIntentByCode("F001", "PRODUCTION_REPORT"))
                .thenReturn(Optional.of(intent));
        when(harness.aiIntentService.hasPermission("PRODUCTION_REPORT", "operator"))
                .thenReturn(true);
        when(harness.writeGuardService.isWriteIntent(intent)).thenReturn(true);

        IntentExecuteResponse response = harness.orchestrator.execute(
                "F001",
                IntentExecuteRequest.builder()
                        .userInput("我要报工")
                        .intentCode("PRODUCTION_REPORT")
                        .forceExecute(true)
                        .build(),
                7L,
                "operator");

        assertThat(response.getStatus()).isEqualTo("FACTORY_PACK_WORKFLOW_GUIDANCE");
        assertThat(response.getMetadata())
                .containsEntry("workflowReference", "FORM:PRODUCTION_REPORT")
                .containsEntry("mutation", true);
        verify(harness.aiIntentService).hasPermission("PRODUCTION_REPORT", "operator");
        verify(harness.writeGuardService).isWriteIntent(intent);
        verify(harness.writeGuardService, never()).isConfirmed(any());
        verifyNoInteractions(harness.toolRegistry, harness.toolDispatchService);
    }

    @Test
    void phraseFastPathWithSessionStillPassesPackAndSkipsContinuationAndExtraLlm() {
        Harness harness = new Harness();
        AIIntentConfig intent = intent(
                "PROCESSING_BATCH_DETAIL", "processing_batch_detail", "批次详情");
        ToolExecutor executor = mock(ToolExecutor.class);
        // spec §8.2: 读工具须显式声明 READ (未声明按 WRITE 是 fail-closed 底线)
        when(executor.getAccessMode()).thenReturn(ToolExecutor.AccessMode.READ);
        IntentExecuteResponse executed = IntentExecuteResponse.builder()
                .intentRecognized(true)
                .intentCode("PROCESSING_BATCH_DETAIL")
                .status("COMPLETED")
                .message("批次进度查询完成")
                .build();
        when(harness.knowledgeBase.matchPhrase("查看批次进度", "FACTORY"))
                .thenReturn(Optional.of("PROCESSING_BATCH_DETAIL"));
        when(harness.aiIntentService.getIntentByCode("F001", "PROCESSING_BATCH_DETAIL"))
                .thenReturn(Optional.of(intent));
        when(harness.aiIntentService.hasPermission("PROCESSING_BATCH_DETAIL", "operator"))
                .thenReturn(true);
        when(harness.writeGuardService.isWriteIntent(intent)).thenReturn(false);
        when(harness.toolRegistry.getExecutor("processing_batch_detail"))
                .thenReturn(Optional.of(executor));
        when(harness.toolDispatchService.executeWithTool(
                any(), anyString(), any(), any(), anyLong(), anyString(), any()))
                .thenReturn(executed);

        IntentExecuteResponse response = harness.orchestrator.execute(
                "F001",
                IntentExecuteRequest.builder()
                        .userInput("查看批次进度")
                        .sessionId("continued-session")
                        .build(),
                7L,
                "operator");

        assertThat(response.getStatus()).isEqualTo("COMPLETED");
        verify(harness.toolDispatchService).executeWithTool(
                any(), anyString(), any(), any(), anyLong(), anyString(), any());
        verify(harness.aiIntentService, never()).recognizeIntentWithConfidence(
                anyString(), anyString(), anyInt(), anyLong(), anyString(), anyString(), any(), any());
        verifyNoInteractions(
                harness.conversationService,
                harness.restaurantSelector,
                harness.analysisRouterService,
                harness.agentOrchestrator,
                harness.ragRouterService,
                harness.dashScopeClient,
                harness.ownerToolGateway,
                harness.principalFactory);
        verify(harness.factoryRepository, times(1)).findById("F001");
    }

    @Test
    void recognizerSelectedPackExternalToolReturnsControlledNoMatch() {
        Harness harness = new Harness();
        AIIntentConfig intent = intent("REPORT_INVENTORY", "report_inventory", "库存报表");
        IntentMatchResult match = IntentMatchResult.builder()
                .bestMatch(intent)
                .confidence(0.92)
                .matchMethod(IntentMatchResult.MatchMethod.LLM)
                .requiresConfirmation(false)
                .questionType(IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND)
                .build();
        when(harness.aiIntentService.recognizeIntentWithConfidence(
                "查看批次进度", "F001", 3, 7L, "operator", null, null, null)).thenReturn(match);
        when(harness.aiIntentService.hasPermission("REPORT_INVENTORY", "operator"))
                .thenReturn(true);
        when(harness.writeGuardService.isWriteIntent(intent)).thenReturn(false);

        IntentExecuteResponse response = harness.orchestrator.execute(
                "F001",
                IntentExecuteRequest.builder().userInput("查看批次进度").build(),
                7L,
                "operator");

        assertThat(response.getStatus()).isEqualTo("FACTORY_PACK_NO_MATCH");
        assertThat(response.getMetadata())
                .containsEntry("reason", "read-tool-outside-allowlist");
        verifyNoInteractions(harness.restaurantSelector, harness.toolRegistry, harness.toolDispatchService);
    }

    @Test
    void generalAnalysisClassificationCannotReachAnalysisRagOrConversationalLlm() {
        Harness harness = new Harness();
        IntentMatchResult general = IntentMatchResult.builder()
                .confidence(0.0)
                .matchMethod(IntentMatchResult.MatchMethod.NONE)
                .questionType(IntentKnowledgeBase.QuestionType.GENERAL_QUESTION)
                .build();
        when(harness.aiIntentService.recognizeIntentWithConfidence(
                "分析生产异常", "F001", 3, 7L, "dispatcher", null, null, null)).thenReturn(general);

        IntentExecuteResponse response = harness.orchestrator.execute(
                "F001",
                IntentExecuteRequest.builder().userInput("分析生产异常").build(),
                7L,
                "dispatcher");

        assertThat(response.getStatus()).isEqualTo("FACTORY_PACK_NO_MATCH");
        assertThat(response.getMetadata())
                .containsEntry("reason", "general-analysis-not-allowed");
        verifyNoInteractions(
                harness.restaurantSelector,
                harness.analysisRouterService,
                harness.agentOrchestrator,
                harness.ragRouterService,
                harness.dashScopeClient,
                harness.ownerToolGateway,
                harness.principalFactory);
    }

    private static AIIntentConfig intent(String code, String toolName, String name) {
        return AIIntentConfig.builder()
                .intentCode(code)
                .intentName(name)
                .intentCategory("QUERY")
                .businessType("FACTORY")
                .sensitivityLevel("LOW")
                .toolName(toolName)
                .build();
    }

    private static final class Harness {
        private final AIIntentService aiIntentService = mock(AIIntentService.class);
        private final SemanticCacheService semanticCacheService = mock(SemanticCacheService.class);
        private final ConversationService conversationService = mock(ConversationService.class);
        private final ConversationMemoryService conversationMemoryService =
                mock(ConversationMemoryService.class);
        private final DashScopeClient dashScopeClient = mock(DashScopeClient.class);
        private final IntentKnowledgeBase knowledgeBase = mock(IntentKnowledgeBase.class);
        private final ToolRegistry toolRegistry = mock(ToolRegistry.class);
        private final AnalysisRouterService analysisRouterService = mock(AnalysisRouterService.class);
        private final AgentOrchestrator agentOrchestrator = mock(AgentOrchestrator.class);
        private final AgenticRAGRouterService ragRouterService = mock(AgenticRAGRouterService.class);
        private final ToolDispatchService toolDispatchService = mock(ToolDispatchService.class);
        private final QueryPreprocessorService queryPreprocessorService =
                mock(QueryPreprocessorService.class);
        private final WriteGuardService writeGuardService = mock(WriteGuardService.class);
        private final RestaurantGrossMarginChatRouteSelector restaurantSelector =
                mock(RestaurantGrossMarginChatRouteSelector.class);
        private final ToolExecutionGateway ownerToolGateway = mock(ToolExecutionGateway.class);
        private final AuthenticatedToolPrincipalFactory principalFactory =
                mock(AuthenticatedToolPrincipalFactory.class);
        private final FactoryRepository factoryRepository = mock(FactoryRepository.class);
        private final IntentExecutionOrchestrator orchestrator;

        private Harness() {
            Factory factory = new Factory();
            factory.setId("F001");
            factory.setType(FactoryType.FACTORY);
            factory.setIsActive(true);
            when(factoryRepository.findById("F001")).thenReturn(Optional.of(factory));
            when(queryPreprocessorService.detectNegationVeto(anyString(), any()))
                    .thenReturn(QueryPreprocessorService.NegationKind.NONE);
            when(semanticCacheService.queryCache(anyString(), anyString()))
                    .thenReturn(SemanticCacheHit.miss(0));

            FactoryCapabilityPackRoutingPolicy policy = new FactoryCapabilityPackRoutingPolicy(
                    factoryRepository,
                    new FactoryCapabilityPackSelector(new FactoryCapabilityPackRegistry()),
                    true);
            orchestrator = new IntentExecutionOrchestrator(
                    aiIntentService,
                    mock(IntentSemanticsParser.class),
                    semanticCacheService,
                    mock(RuleEngineService.class),
                    conversationService,
                    conversationMemoryService,
                    new ObjectMapper(),
                    dashScopeClient,
                    mock(DashScopeConfig.class),
                    knowledgeBase,
                    mock(AIAnalysisResultRepository.class),
                    toolRegistry,
                    analysisRouterService,
                    mock(ComplexityRouter.class),
                    agentOrchestrator,
                    ragRouterService,
                    mock(ResultValidatorService.class),
                    toolDispatchService,
                    mock(DynamicToolSelectionService.class),
                    queryPreprocessorService);
            ReflectionTestUtils.setField(orchestrator, "factoryCapabilityPackRoutingPolicy", policy);
            ReflectionTestUtils.setField(orchestrator, "writeGuardService", writeGuardService);
            ReflectionTestUtils.setField(
                    orchestrator, "restaurantGrossMarginChatRouteSelector", restaurantSelector);
            ReflectionTestUtils.setField(orchestrator, "toolExecutionGateway", ownerToolGateway);
            ReflectionTestUtils.setField(
                    orchestrator, "authenticatedToolPrincipalFactory", principalFactory);
            ReflectionTestUtils.setField(orchestrator, "businessTypeGate", mock(BusinessTypeGate.class));
        }
    }
}
