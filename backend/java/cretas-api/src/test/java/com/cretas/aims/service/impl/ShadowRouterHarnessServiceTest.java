package com.cretas.aims.service.impl;

import com.cretas.aims.dto.intent.RouteDecision;
import com.cretas.aims.repository.IntentMatchRecordRepository;
import com.cretas.aims.service.SemanticRouterService;
import com.cretas.aims.config.IntentMatchingConfig;
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import java.math.BigDecimal;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * Unit tests for ShadowRouterHarnessService (W1a shadow router harness).
 *
 * NOTE: RouteDecision.directExecute(intent, ...) calls intent.getIntentCode() — passing null
 * would NPE. We build RouteDecision via builder + setBestMatchIntentCode() instead, which is
 * exactly how the harness reads the decision (via getBestMatchIntentCode()). The @Data annotation
 * on RouteDecision generates setBestMatchIntentCode(String), so this is safe.
 */
@ExtendWith(MockitoExtension.class)
class ShadowRouterHarnessServiceTest {

    @Mock SemanticRouterService semanticRouterService;
    @Mock IntentMatchRecordRepository recordRepository;
    @Mock IntentMatchingConfig matchingConfig;
    @InjectMocks ShadowRouterHarnessService harness;

    @BeforeEach
    void init() {
        when(matchingConfig.getShadowModeSampleRate()).thenReturn(1.0);
    }

    /** Helper: build a RouteDecision with a specific intentCode without NPE from factory method. */
    private RouteDecision buildDecision(String intentCode, double score, RouteDecision.RouteType type) {
        RouteDecision d = RouteDecision.builder()
                .routeType(type)
                .topScore(score)
                .candidates(List.of())
                .userInput("test-input")
                .routeLatencyMs(5L)
                .build();
        d.setBestMatchIntentCode(intentCode);
        return d;
    }

    @Test
    void agreement_writesShadowResult_noZpd() {
        RouteDecision d = buildDecision("MATERIAL_BATCH_QUERY", 0.91, RouteDecision.RouteType.DIRECT_EXECUTE);
        when(semanticRouterService.route("F001", "查库存", 1)).thenReturn(d);

        harness.shadowRoute("rec-1", "查库存", "MATERIAL_BATCH_QUERY", 0.95, "F001");

        verify(recordRepository).updateShadowResult(
                eq("rec-1"), eq("MATERIAL_BATCH_QUERY"), any(BigDecimal.class),
                eq(true), any(), any(), any());
        verify(recordRepository, never()).markZpdBoundary(anyString());
    }

    @Test
    void disagreement_writesShadowResult_andZpd() {
        RouteDecision d = buildDecision("REPORT_INVENTORY", 0.80, RouteDecision.RouteType.DIRECT_EXECUTE);
        when(semanticRouterService.route("F001", "查库存", 1)).thenReturn(d);

        harness.shadowRoute("rec-2", "查库存", "MATERIAL_BATCH_QUERY", 0.95, "F001");

        verify(recordRepository).updateShadowResult(
                eq("rec-2"), eq("REPORT_INVENTORY"), any(BigDecimal.class),
                eq(false), any(), any(), any());
        verify(recordRepository).markZpdBoundary("rec-2");
    }

    @Test
    void nullChallenger_writesDisagree() {
        // needFullLLM produces bestMatchIntentCode=null
        RouteDecision d = RouteDecision.needFullLLM(0.40, List.of(), "随便聊聊", 5L);
        when(semanticRouterService.route("F001", "随便聊聊", 1)).thenReturn(d);

        harness.shadowRoute("rec-3", "随便聊聊", "OUT_OF_DOMAIN", 0.9, "F001");

        verify(recordRepository).updateShadowResult(
                eq("rec-3"), isNull(), any(), eq(false), any(), any(), any());
    }

    @Test
    void neverThrowsIntoCaller_onRouterError() {
        when(semanticRouterService.route(anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("boom"));

        Assertions.assertDoesNotThrow(
                () -> harness.shadowRoute("rec-4", "x", "Y", 0.5, "F001"));
        verify(recordRepository, never()).updateShadowResult(
                any(), any(), any(), anyBoolean(), any(), any(), any());
    }
}
