package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.service.*;
import com.cretas.aims.service.restaurant.RestaurantGrossMarginChatRouteSelector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IntentExecutionOrchestratorRestaurantAgentRouteTest {

    @Test
    void selectsBoundedRuntimeBeforeLegacyOwnerActionAndIntentRecognition() {
        AIIntentService aiIntentService = mock(AIIntentService.class);
        QueryPreprocessorService preprocessor = mock(QueryPreprocessorService.class);
        RestaurantGrossMarginChatRouteSelector selector =
                mock(RestaurantGrossMarginChatRouteSelector.class);
        IntentExecuteResponse selected = IntentExecuteResponse.builder()
                .intentRecognized(true)
                .intentCode("GROSS_MARGIN_DECLINE_ATTRIBUTION")
                .status("READY")
                .metadata(Map.of("agentRun", Map.of("autoStart", true)))
                .build();
        when(selector.select("REST-1", "为什么本月毛利下降", "restaurant_owner"))
                .thenReturn(Optional.of(selected));

        IntentExecutionOrchestrator orchestrator = newOrchestrator(aiIntentService, preprocessor);
        ReflectionTestUtils.setField(
                orchestrator, "restaurantGrossMarginChatRouteSelector", selector);
        IntentExecuteRequest request = IntentExecuteRequest.builder()
                .userInput("为什么本月毛利下降")
                .build();

        IntentExecuteResponse actual = orchestrator.execute(
                "REST-1", request, 42L, "restaurant_owner");

        assertThat(actual).isSameAs(selected);
        verify(selector).select("REST-1", "为什么本月毛利下降", "restaurant_owner");
        verifyNoInteractions(aiIntentService, preprocessor);
    }

    @Test
    void explicitIntentNeverCallsNaturalLanguageSelector() {
        AIIntentService aiIntentService = mock(AIIntentService.class);
        QueryPreprocessorService preprocessor = mock(QueryPreprocessorService.class);
        RestaurantGrossMarginChatRouteSelector selector =
                mock(RestaurantGrossMarginChatRouteSelector.class);
        IntentExecutionOrchestrator orchestrator = spy(newOrchestrator(aiIntentService, preprocessor));
        ReflectionTestUtils.setField(
                orchestrator, "restaurantGrossMarginChatRouteSelector", selector);
        IntentExecuteRequest request = IntentExecuteRequest.builder()
                .intentCode("RESTAURANT_MONTHLY_REPORT")
                .userInput("为什么本月毛利下降")
                .build();
        IntentExecuteResponse explicit = IntentExecuteResponse.builder()
                .intentCode("RESTAURANT_MONTHLY_REPORT")
                .status("SUCCESS")
                .build();
        doReturn(explicit).when(orchestrator).executeWithExplicitIntent(
                "REST-1", request, 42L, "restaurant_owner");

        IntentExecuteResponse actual = orchestrator.execute(
                "REST-1", request, 42L, "restaurant_owner");

        assertThat(actual).isSameAs(explicit);
        verifyNoInteractions(selector);
    }

    @Test
    void previewNeverCallsAutoStartSelector() {
        AIIntentService aiIntentService = mock(AIIntentService.class);
        QueryPreprocessorService preprocessor = mock(QueryPreprocessorService.class);
        RestaurantGrossMarginChatRouteSelector selector =
                mock(RestaurantGrossMarginChatRouteSelector.class);
        when(preprocessor.detectNegationVeto(any(), any()))
                .thenReturn(QueryPreprocessorService.NegationKind.VETO_READ);
        IntentExecutionOrchestrator orchestrator = newOrchestrator(aiIntentService, preprocessor);
        ReflectionTestUtils.setField(
                orchestrator, "restaurantGrossMarginChatRouteSelector", selector);
        IntentExecuteRequest request = IntentExecuteRequest.builder()
                .userInput("不要查订单")
                .previewOnly(true)
                .build();

        IntentExecuteResponse actual = orchestrator.execute(
                "REST-1", request, 42L, "restaurant_owner");

        assertThat(actual.getStatus()).isEqualTo("NEED_CLARIFICATION");
        verifyNoInteractions(selector);
    }

    private IntentExecutionOrchestrator newOrchestrator(
            AIIntentService aiIntentService,
            QueryPreprocessorService preprocessor) {
        return new IntentExecutionOrchestrator(
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
                preprocessor);
    }
}
