package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("IntentExecutionOrchestrator restaurant owner-action domain guard")
class IntentExecutionOrchestratorRestaurantOwnerActionTest {

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
    void allowsRestaurantDemoAndRestaurantPrefixesWithoutPollutingFactoryTenants() {
        assertThat(orchestrator.isRestaurantOwnerActionFactory("DEMO_REST", null)).isTrue();
        assertThat(orchestrator.isRestaurantOwnerActionFactory("res_3101_009", null)).isTrue();
        assertThat(orchestrator.isRestaurantOwnerActionFactory("REST_SHOP_001", null)).isTrue();
        assertThat(orchestrator.isRestaurantOwnerActionFactory("ANY_ID", "RESTAURANT")).isTrue();

        assertThat(orchestrator.isRestaurantOwnerActionFactory("F006", null)).isFalse();
        assertThat(orchestrator.isRestaurantOwnerActionFactory("F006", "FACTORY")).isFalse();
        assertThat(orchestrator.isRestaurantOwnerActionFactory(null, null)).isFalse();
    }
}
