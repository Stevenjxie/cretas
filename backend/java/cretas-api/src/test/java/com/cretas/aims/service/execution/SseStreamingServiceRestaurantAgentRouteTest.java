package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.service.*;
import com.cretas.aims.service.restaurant.RestaurantGrossMarginChatRouteSelector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SseStreamingServiceRestaurantAgentRouteTest {

    @Test
    void streamUsesSameSelectorBeforeQuestionDetectionCacheAndRecognition() throws Exception {
        AIIntentService aiIntentService = mock(AIIntentService.class);
        SemanticCacheService semanticCacheService = mock(SemanticCacheService.class);
        IntentKnowledgeBase knowledgeBase = mock(IntentKnowledgeBase.class);
        RestaurantGrossMarginChatRouteSelector selector =
                mock(RestaurantGrossMarginChatRouteSelector.class);
        IntentExecuteResponse selected = IntentExecuteResponse.builder()
                .intentRecognized(true)
                .intentCode("GROSS_MARGIN_DECLINE_ATTRIBUTION")
                .intentName("本月毛利下降归因")
                .intentCategory("ANALYSIS")
                .confidence(1.0d)
                .matchMethod("DETERMINISTIC")
                .status("READY")
                .metadata(Map.of("agentRun", Map.of("autoStart", true)))
                .build();
        when(selector.select("REST-1", "分析本月毛利下滑原因", "restaurant_owner"))
                .thenReturn(Optional.of(selected));

        SseStreamingService service = spy(new SseStreamingService(
                aiIntentService,
                semanticCacheService,
                knowledgeBase,
                mock(AnalysisRouterService.class),
                new ObjectMapper(),
                mock(ToolRegistry.class),
                mock(ToolDispatchService.class),
                mock(DynamicToolSelectionService.class),
                mock(DashScopeClient.class),
                mock(DashScopeConfig.class)));
        ReflectionTestUtils.setField(
                service, "restaurantGrossMarginChatRouteSelector", selector);
        doNothing().when(service).sendSseEvent(any(), any(), any());
        SseEmitter emitter = mock(SseEmitter.class);
        IntentExecuteRequest request = IntentExecuteRequest.builder()
                .userInput("分析本月毛利下滑原因")
                .build();

        ReflectionTestUtils.invokeMethod(
                service, "executeStreamAsync", emitter, "REST-1", request, 42L,
                "restaurant_owner");

        verify(selector).select("REST-1", "分析本月毛利下滑原因", "restaurant_owner");
        verify(service).sendSseEvent(eq(emitter), eq("start"), any());
        verify(service).sendSseEvent(eq(emitter), eq("intent_recognized"), any());
        verify(service).sendSseEvent(eq(emitter), eq("result"), eq(selected));
        verify(service).sendSseEvent(eq(emitter), eq("complete"), any());
        verify(service, times(4)).sendSseEvent(eq(emitter), any(), any());
        verify(emitter).complete();
        verifyNoInteractions(aiIntentService, semanticCacheService, knowledgeBase);
    }

    @Test
    void explicitIntentAndPreviewDoNotAdvertiseAnAutoStartingRuntime() throws Exception {
        RestaurantGrossMarginChatRouteSelector selector =
                mock(RestaurantGrossMarginChatRouteSelector.class);
        SseStreamingService service = spy(new SseStreamingService(
                mock(AIIntentService.class),
                mock(SemanticCacheService.class),
                mock(IntentKnowledgeBase.class),
                mock(AnalysisRouterService.class),
                new ObjectMapper(),
                mock(ToolRegistry.class),
                mock(ToolDispatchService.class),
                mock(DynamicToolSelectionService.class),
                mock(DashScopeClient.class),
                mock(DashScopeConfig.class)));
        ReflectionTestUtils.setField(
                service, "restaurantGrossMarginChatRouteSelector", selector);
        doNothing().when(service).sendSseEvent(any(), any(), any());
        IntentExecuteRequest previewRequest = IntentExecuteRequest.builder()
                .previewOnly(true)
                .build();
        IntentExecuteRequest explicitRequest = IntentExecuteRequest.builder()
                .intentCode("RESTAURANT_MONTHLY_REPORT")
                .build();

        ReflectionTestUtils.invokeMethod(
                service, "executeStreamAsync", mock(SseEmitter.class), "REST-1", previewRequest, 42L,
                "restaurant_owner");
        ReflectionTestUtils.invokeMethod(
                service, "executeStreamAsync", mock(SseEmitter.class), "REST-1", explicitRequest, 42L,
                "restaurant_owner");

        verifyNoInteractions(selector);
    }
}
