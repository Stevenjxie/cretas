package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.impl.restaurant.TieredIntentDelegate;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.AnalysisRouterService;
import com.cretas.aims.service.SemanticCacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Card4 (2026-07-28): restaurant tiered-first parity on the SSE channel, aligned to
 * IntentExecutionOrchestrator.execute() :371-400. Covers the accept path, the fail-closed
 * path, and the "restaurant tenants never read the SSE semantic cache" gate at :262-299
 * (renumbered) even when tiered-first itself didn't fire for the request (e.g. a write verb).
 */
class SseStreamingServiceTieredFirstTest {

    private AIIntentService aiIntentService;
    private SemanticCacheService semanticCacheService;
    private IntentKnowledgeBase knowledgeBase;
    private TieredIntentDelegate delegate;
    private SseStreamingService service;
    private SseEmitter emitter;

    private void setUp() throws Exception {
        aiIntentService = mock(AIIntentService.class);
        semanticCacheService = mock(SemanticCacheService.class);
        knowledgeBase = mock(IntentKnowledgeBase.class);
        delegate = mock(TieredIntentDelegate.class);
        service = spy(new SseStreamingService(
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
        ReflectionTestUtils.setField(service, "sseTieredIntentDelegate", delegate);
        ReflectionTestUtils.setField(service, "tieredFirstEnabled", true);
        doNothing().when(service).sendSseEvent(any(), any(), any());
        emitter = mock(SseEmitter.class);
    }

    @Test
    void tieredFirstAcceptsAndShortCircuitsCacheRecognitionAndBoundedRoute() throws Exception {
        setUp();
        Map<String, Object> delegated = new LinkedHashMap<>();
        delegated.put("message", "本月毛利环比上升 2.3 个百分点");
        delegated.put("code", "GROSS_MARGIN_TREND");
        when(delegate.tryDelegate(eq("DEMO_REST"), any(), any(), eq("sse_tiered_first")))
                .thenReturn(delegated);
        IntentExecuteRequest request = IntentExecuteRequest.builder()
                .userInput("本月毛利趋势怎么样")
                .build();

        ReflectionTestUtils.invokeMethod(
                service, "executeStreamAsync", emitter, "DEMO_REST", request, 42L, "restaurant_owner");

        org.mockito.ArgumentCaptor<Object> resultCaptor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(service).sendSseEvent(eq(emitter), eq("result"), resultCaptor.capture());
        IntentExecuteResponse response = (IntentExecuteResponse) resultCaptor.getValue();
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getIntentRecognized()).isTrue();
        assertThat(response.getMessage()).isEqualTo("本月毛利环比上升 2.3 个百分点");
        verify(service).sendSseEvent(eq(emitter), eq("complete"), any());
        verify(emitter).complete();
        verify(delegate).tryDelegate(eq("DEMO_REST"), any(), any(), eq("sse_tiered_first"));
        verifyNoInteractions(aiIntentService, semanticCacheService, knowledgeBase);
    }

    @Test
    void tieredFirstFailsClosedWhenDelegateHasNoAnswer() throws Exception {
        setUp();
        when(delegate.tryDelegate(eq("DEMO_REST"), any(), any(), eq("sse_tiered_first")))
                .thenReturn(null);
        IntentExecuteRequest request = IntentExecuteRequest.builder()
                .userInput("本月毛利趋势怎么样")
                .build();

        ReflectionTestUtils.invokeMethod(
                service, "executeStreamAsync", emitter, "DEMO_REST", request, 42L, "restaurant_owner");

        org.mockito.ArgumentCaptor<Object> resultCaptor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(service).sendSseEvent(eq(emitter), eq("result"), resultCaptor.capture());
        IntentExecuteResponse response = (IntentExecuteResponse) resultCaptor.getValue();
        assertThat(response.getStatus()).isEqualTo("NEED_CLARIFICATION");
        assertThat(response.getMessage()).contains("餐饮语义规划暂时不可用");
        verify(emitter).complete();
        verifyNoInteractions(aiIntentService, semanticCacheService, knowledgeBase);
    }

    @Test
    void restaurantWriteVerbSkipsTieredFirstButStillSkipsSemanticCache() throws Exception {
        setUp();
        IntentMatchResult noMatch = IntentMatchResult.builder()
                .confidence(0.0d)
                .matchMethod(IntentMatchResult.MatchMethod.NONE)
                .questionType(IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND)
                .build();
        when(aiIntentService.recognizeIntentWithConfidence(
                anyString(), eq("DEMO_REST"), org.mockito.ArgumentMatchers.eq(3),
                any(), anyString(), any()))
                .thenReturn(noMatch);
        IntentExecuteRequest request = IntentExecuteRequest.builder()
                .userInput("帮我创建一个新活动")
                .build();

        ReflectionTestUtils.invokeMethod(
                service, "executeStreamAsync", emitter, "DEMO_REST", request, 42L, "restaurant_owner");

        verifyNoInteractions(delegate);
        verify(semanticCacheService, never()).queryCache(anyString(), anyString());
    }
}
