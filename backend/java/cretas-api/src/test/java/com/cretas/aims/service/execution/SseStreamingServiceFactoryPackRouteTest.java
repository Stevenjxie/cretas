package com.cretas.aims.service.execution;

import com.cretas.aims.ai.capability.FactoryCapabilityPackRegistry;
import com.cretas.aims.ai.capability.FactoryCapabilityPackRoutingPolicy;
import com.cretas.aims.ai.capability.FactoryCapabilityPackSelector;
import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.AnalysisRouterService;
import com.cretas.aims.service.SemanticCacheService;
import com.cretas.aims.service.restaurant.RestaurantGrossMarginChatRouteSelector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SseStreamingServiceFactoryPackRouteTest {

    @Test
    void disabledPolicyPreservesLegacyRestaurantRoute() throws Exception {
        Harness harness = new Harness(false, FactoryType.RESTAURANT);
        IntentExecuteResponse selected = restaurantResponse();
        when(harness.restaurantSelector.select(
                "REST-1", "分析本月毛利下降原因", "restaurant_owner"))
                .thenReturn(Optional.of(selected));

        IntentExecuteResponse result = harness.execute(
                "REST-1",
                IntentExecuteRequest.builder().userInput("分析本月毛利下降原因").build(),
                "restaurant_owner");

        assertThat(result).isSameAs(selected);
        verify(harness.restaurantSelector).select(
                "REST-1", "分析本月毛利下降原因", "restaurant_owner");
        verifyNoInteractions(harness.factoryRepository);
    }

    @Test
    void enabledPolicyDoesNotInterceptRestaurantRuntime() throws Exception {
        Harness harness = new Harness(true, FactoryType.RESTAURANT);
        IntentExecuteResponse selected = restaurantResponse();
        when(harness.restaurantSelector.select(
                "REST-1", "查看批次进度", "operator"))
                .thenReturn(Optional.of(selected));

        IntentExecuteResponse result = harness.execute(
                "REST-1",
                IntentExecuteRequest.builder().userInput("查看批次进度").build(),
                "operator");

        assertThat(result).isSameAs(selected);
        verify(harness.restaurantSelector).select(
                "REST-1", "查看批次进度", "operator");
        verify(harness.factoryRepository, times(1)).findById("REST-1");
    }

    @Test
    void packExternalQueryFailsClosedBeforeEveryLegacyRoute() throws Exception {
        Harness harness = new Harness(true, FactoryType.FACTORY);

        IntentExecuteResponse result = harness.execute(
                "F001",
                IntentExecuteRequest.builder().userInput("审批付款").build(),
                "operator");

        assertThat(result.getStatus()).isEqualTo("FACTORY_PACK_NO_MATCH");
        assertThat(result.getMetadata())
                .containsEntry("reason", "outside-pack-domain")
                .containsEntry("packId", "factory.operator");
        verify(harness.factoryRepository, times(1)).findById("F001");
        verifyNoInteractions(
                harness.restaurantSelector,
                harness.knowledgeBase,
                harness.semanticCacheService,
                harness.aiIntentService,
                harness.analysisRouterService,
                harness.dynamicToolSelectionService,
                harness.dashScopeClient,
                harness.toolRegistry,
                harness.toolDispatchService,
                harness.writeGuardService,
                harness.businessTypeGate);
    }

    @Test
    void allowlistedReadUsesExistingToolPathWithoutCacheSkillOrGeneralLlm() throws Exception {
        Harness harness = new Harness(true, FactoryType.FACTORY);
        AIIntentConfig intent = intent(
                "PROCESSING_BATCH_DETAIL", "processing_batch_detail", "批次详情");
        IntentMatchResult match = match(intent, IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND);
        ToolExecutor executor = mock(ToolExecutor.class);
        IntentExecuteResponse executed = IntentExecuteResponse.builder()
                .intentRecognized(true)
                .intentCode(intent.getIntentCode())
                .status("COMPLETED")
                .message("批次进度查询完成")
                .build();
        when(harness.aiIntentService.recognizeIntentWithConfidence(
                "查看批次进度", "F001", 3, 7L, "operator", null)).thenReturn(match);
        when(harness.aiIntentService.hasPermission(intent.getIntentCode(), "operator"))
                .thenReturn(true);
        when(harness.writeGuardService.isWriteIntent(intent)).thenReturn(false);
        when(harness.toolRegistry.getExecutor(intent.getToolName())).thenReturn(Optional.of(executor));
        when(harness.toolDispatchService.executeWithTool(
                eq(executor), eq("F001"), any(), eq(intent), eq(7L), eq("operator"), eq(match)))
                .thenReturn(executed);

        IntentExecuteResponse result = harness.execute(
                "F001",
                IntentExecuteRequest.builder().userInput("查看批次进度").build(),
                "operator");

        assertThat(result).isSameAs(executed);
        verify(harness.toolDispatchService).executeWithTool(
                eq(executor), eq("F001"), any(), eq(intent), eq(7L), eq("operator"), eq(match));
        verify(harness.semanticCacheService, never()).queryCache(anyString(), anyString());
        verifyNoInteractions(
                harness.restaurantSelector,
                harness.knowledgeBase,
                harness.analysisRouterService,
                harness.dynamicToolSelectionService,
                harness.dashScopeClient,
                harness.businessTypeGate);
        verify(harness.factoryRepository, times(1)).findById("F001");
    }

    @Test
    void explicitWriteReturnsWorkflowGuidanceAndNeverReachesToolOrSkill() throws Exception {
        Harness harness = new Harness(true, FactoryType.FACTORY);
        AIIntentConfig intent = intent(
                "PRODUCTION_REPORT", "production_report_submit", "生产报工");
        when(harness.aiIntentService.getIntentByCode("F001", "PRODUCTION_REPORT"))
                .thenReturn(Optional.of(intent));
        when(harness.aiIntentService.hasPermission("PRODUCTION_REPORT", "operator"))
                .thenReturn(true);
        when(harness.writeGuardService.isWriteIntent(intent)).thenReturn(true);

        IntentExecuteResponse result = harness.execute(
                "F001",
                IntentExecuteRequest.builder()
                        .userInput("我要报工")
                        .intentCode("PRODUCTION_REPORT")
                        .forceExecute(true)
                        .build(),
                "operator");

        assertThat(result.getStatus()).isEqualTo("FACTORY_PACK_WORKFLOW_GUIDANCE");
        assertThat(result.getMetadata())
                .containsEntry("workflowReference", "FORM:PRODUCTION_REPORT")
                .containsEntry("mutation", true);
        assertThat(result.getSuggestedActions()).singleElement().satisfies(action ->
                assertThat(action.getParameters())
                        .containsEntry("requiresUserConfirmation", true));
        verify(harness.aiIntentService, never()).recognizeIntentWithConfidence(
                anyString(), anyString(), anyInt(), anyLong(), anyString(), any());
        verifyNoInteractions(
                harness.restaurantSelector,
                harness.semanticCacheService,
                harness.knowledgeBase,
                harness.analysisRouterService,
                harness.dynamicToolSelectionService,
                harness.dashScopeClient,
                harness.toolRegistry,
                harness.toolDispatchService,
                harness.businessTypeGate);
    }

    @Test
    void recognizerSelectedExternalToolCannotFallThroughToLegacySkillOrTool() throws Exception {
        Harness harness = new Harness(true, FactoryType.FACTORY);
        AIIntentConfig intent = intent("REPORT_INVENTORY", "report_inventory", "库存报表");
        IntentMatchResult match = match(intent, IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND);
        when(harness.aiIntentService.recognizeIntentWithConfidence(
                "查看批次进度", "F001", 3, 7L, "operator", null)).thenReturn(match);
        when(harness.aiIntentService.hasPermission("REPORT_INVENTORY", "operator"))
                .thenReturn(true);
        when(harness.writeGuardService.isWriteIntent(intent)).thenReturn(false);

        IntentExecuteResponse result = harness.execute(
                "F001",
                IntentExecuteRequest.builder().userInput("查看批次进度").build(),
                "operator");

        assertThat(result.getStatus()).isEqualTo("FACTORY_PACK_NO_MATCH");
        assertThat(result.getMetadata())
                .containsEntry("reason", "read-tool-outside-allowlist");
        verifyNoInteractions(
                harness.restaurantSelector,
                harness.knowledgeBase,
                harness.semanticCacheService,
                harness.analysisRouterService,
                harness.dynamicToolSelectionService,
                harness.dashScopeClient,
                harness.toolRegistry,
                harness.toolDispatchService,
                harness.businessTypeGate);
    }

    @Test
    void recognizerGeneralClassificationCannotReachConversationalLlmOrSkill() throws Exception {
        Harness harness = new Harness(true, FactoryType.CENTRAL_KITCHEN);
        IntentMatchResult general = IntentMatchResult.builder()
                .confidence(0.0d)
                .matchMethod(IntentMatchResult.MatchMethod.NONE)
                .questionType(IntentKnowledgeBase.QuestionType.GENERAL_QUESTION)
                .build();
        when(harness.aiIntentService.recognizeIntentWithConfidence(
                "分析生产异常", "F001", 3, 7L, "operator", null)).thenReturn(general);

        IntentExecuteResponse result = harness.execute(
                "F001",
                IntentExecuteRequest.builder().userInput("分析生产异常").build(),
                "operator");

        assertThat(result.getStatus()).isEqualTo("FACTORY_PACK_NO_MATCH");
        assertThat(result.getMetadata())
                .containsEntry("reason", "general-analysis-not-allowed");
        verifyNoInteractions(
                harness.restaurantSelector,
                harness.knowledgeBase,
                harness.semanticCacheService,
                harness.analysisRouterService,
                harness.dynamicToolSelectionService,
                harness.dashScopeClient,
                harness.toolRegistry,
                harness.toolDispatchService,
                harness.writeGuardService,
                harness.businessTypeGate);
    }

    private static IntentMatchResult match(
            AIIntentConfig intent, IntentKnowledgeBase.QuestionType questionType) {
        return IntentMatchResult.builder()
                .bestMatch(intent)
                .confidence(0.96d)
                .matchMethod(IntentMatchResult.MatchMethod.PHRASE_MATCH)
                .requiresConfirmation(false)
                .questionType(questionType)
                .build();
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

    private static IntentExecuteResponse restaurantResponse() {
        return IntentExecuteResponse.builder()
                .intentRecognized(true)
                .intentCode("GROSS_MARGIN_DECLINE_ATTRIBUTION")
                .intentName("本月毛利下降归因")
                .intentCategory("ANALYSIS")
                .confidence(1.0d)
                .matchMethod("DETERMINISTIC")
                .status("READY")
                .metadata(Map.of("agentRun", Map.of("autoStart", true)))
                .build();
    }

    private static final class Harness {
        private final AIIntentService aiIntentService = mock(AIIntentService.class);
        private final SemanticCacheService semanticCacheService = mock(SemanticCacheService.class);
        private final IntentKnowledgeBase knowledgeBase = mock(IntentKnowledgeBase.class);
        private final AnalysisRouterService analysisRouterService = mock(AnalysisRouterService.class);
        private final ToolRegistry toolRegistry = mock(ToolRegistry.class);
        private final ToolDispatchService toolDispatchService = mock(ToolDispatchService.class);
        private final DynamicToolSelectionService dynamicToolSelectionService =
                mock(DynamicToolSelectionService.class);
        private final DashScopeClient dashScopeClient = mock(DashScopeClient.class);
        private final RestaurantGrossMarginChatRouteSelector restaurantSelector =
                mock(RestaurantGrossMarginChatRouteSelector.class);
        private final WriteGuardService writeGuardService = mock(WriteGuardService.class);
        private final BusinessTypeGate businessTypeGate = mock(BusinessTypeGate.class);
        private final FactoryRepository factoryRepository = mock(FactoryRepository.class);
        private final SseStreamingService service;
        private final SseEmitter emitter = mock(SseEmitter.class);

        private Harness(boolean enabled, FactoryType factoryType) throws Exception {
            Factory factory = new Factory();
            factory.setId("F001");
            factory.setType(factoryType);
            factory.setIsActive(true);
            when(factoryRepository.findById(anyString())).thenReturn(Optional.of(factory));

            FactoryCapabilityPackRoutingPolicy policy = new FactoryCapabilityPackRoutingPolicy(
                    factoryRepository,
                    new FactoryCapabilityPackSelector(new FactoryCapabilityPackRegistry()),
                    enabled);
            service = spy(new SseStreamingService(
                    aiIntentService,
                    semanticCacheService,
                    knowledgeBase,
                    analysisRouterService,
                    new ObjectMapper(),
                    toolRegistry,
                    toolDispatchService,
                    dynamicToolSelectionService,
                    dashScopeClient,
                    mock(DashScopeConfig.class)));
            ReflectionTestUtils.setField(service, "factoryCapabilityPackRoutingPolicy", policy);
            ReflectionTestUtils.setField(service, "writeGuardService", writeGuardService);
            ReflectionTestUtils.setField(service, "businessTypeGate", businessTypeGate);
            ReflectionTestUtils.setField(
                    service, "restaurantGrossMarginChatRouteSelector", restaurantSelector);
            doNothing().when(service).sendSseEvent(any(), anyString(), any());
        }

        private IntentExecuteResponse execute(
                String factoryId, IntentExecuteRequest request, String role) throws Exception {
            ReflectionTestUtils.invokeMethod(
                    service, "executeStreamAsync", emitter, factoryId, request, 7L, role);
            ArgumentCaptor<Object> result = ArgumentCaptor.forClass(Object.class);
            verify(service).sendSseEvent(eq(emitter), eq("result"), result.capture());
            verify(emitter).complete();
            return (IntentExecuteResponse) result.getValue();
        }
    }
}
