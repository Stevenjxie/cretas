package com.cretas.aims.service;

import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.entity.AIQuotaUsage;
import com.cretas.aims.entity.config.AIQuotaConfig;
import com.cretas.aims.repository.AIAuditLogRepository;
import com.cretas.aims.repository.AIQuotaUsageRepository;
import com.cretas.aims.repository.config.AIQuotaConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AIEnterpriseServiceStreamTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void streamUsesTypedTenantBoundTransportAndConsumesQuotaOnlyAfterValidDone()
            throws Exception {
        Fixture fixture = fixture();
        doAnswer(invocation -> {
            PythonSmartBIClient.GeneralAnalysisEventConsumer consumer = invocation.getArgument(4);
            consumer.onEvent(new PythonSmartBIClient.GeneralAnalysisStreamEvent(
                    "status", objectMapper.readTree("\"working\"")));
            consumer.onEvent(new PythonSmartBIClient.GeneralAnalysisStreamEvent(
                    "chunk", objectMapper.readTree("\"answer part\"")));
            consumer.onEvent(new PythonSmartBIClient.GeneralAnalysisStreamEvent(
                    "done", objectMapper.readTree(
                            "{\"success\":true,\"answer\":\"answer part\","
                                    + "\"processingTimeMs\":18}")));
            return null;
        }).when(fixture.pythonClient).streamGeneralAnalysis(
                anyString(), nullable(String.class), nullable(String.class),
                any(PythonSmartBIClient.GeneralAnalysisCall.class), any());

        SseEmitter emitter = fixture.service.analyzeTimeRangeCostStream(
                "FACTORY-SSE",
                77L,
                LocalDateTime.of(2026, 7, 1, 0, 0),
                LocalDateTime.of(2026, 7, 19, 23, 59),
                "overall",
                "分析成本",
                true,
                50,
                fixture.httpRequest);

        ArgumentCaptor<PythonSmartBIClient.GeneralAnalysisCall> requestCaptor =
                ArgumentCaptor.forClass(PythonSmartBIClient.GeneralAnalysisCall.class);
        verify(fixture.pythonClient, timeout(3_000)).streamGeneralAnalysis(
                eq("FACTORY-SSE"),
                eq("77"),
                eq("finance_manager"),
                requestCaptor.capture(),
                any());
        PythonSmartBIClient.GeneralAnalysisCall request = requestCaptor.getValue();
        assertEquals("time_range_cost", request.tableType());
        assertFalse(request.allowTenantDataFallback());
        assertTrue(request.enableThinking());
        assertEquals(50, request.thinkingBudget());
        assertEquals(1, request.data().size());
        assertTrue(request.query().contains("分析成本"));
        verify(fixture.quotaUsageRepository, timeout(3_000))
                .incrementUsedCount(eq("FACTORY-SSE"), any(LocalDate.class), eq(1));
        assertTrue(earlyPayloads(emitter).stream()
                .anyMatch(payload -> "complete".equals(payload.get("type"))));
        assertFalse(earlyPayloads(emitter).stream()
                .anyMatch(payload -> "error".equals(payload.get("type"))));
    }

    @Test
    void streamTransportFailureDoesNotConsumeQuotaOrInventSuccess() throws Exception {
        Fixture fixture = fixture();
        doThrow(new IOException("PRIVATE-UPSTREAM-DETAIL"))
                .when(fixture.pythonClient).streamGeneralAnalysis(
                        anyString(), nullable(String.class), nullable(String.class),
                        any(PythonSmartBIClient.GeneralAnalysisCall.class), any());

        SseEmitter emitter = fixture.service.analyzeTimeRangeCostStream(
                "FACTORY-SSE",
                77L,
                LocalDateTime.of(2026, 7, 1, 0, 0),
                LocalDateTime.of(2026, 7, 19, 23, 59),
                "overall",
                "分析成本",
                false,
                0,
                fixture.httpRequest);

        verify(fixture.pythonClient, timeout(3_000)).streamGeneralAnalysis(
                eq("FACTORY-SSE"), eq("77"), eq("finance_manager"), any(), any());
        verify(fixture.quotaUsageRepository, after(200).never())
                .incrementUsedCount(anyString(), any(LocalDate.class), anyInt());
        verify(fixture.auditLogRepository, never()).save(any());
        List<Map<String, Object>> payloads = earlyPayloads(emitter);
        assertTrue(payloads.stream().anyMatch(payload -> "error".equals(payload.get("type"))));
        assertFalse(payloads.stream().anyMatch(payload -> "complete".equals(payload.get("type"))));
        assertTrue(payloads.stream()
                .filter(payload -> "error".equals(payload.get("type")))
                .noneMatch(payload -> payload.toString().contains("PRIVATE-UPSTREAM-DETAIL")));
    }

    private Fixture fixture() {
        AIEnterpriseService service = new AIEnterpriseService();
        PythonSmartBIClient pythonClient = mock(PythonSmartBIClient.class);
        ProcessingService processingService = mock(ProcessingService.class);
        AIQuotaUsageRepository quotaUsageRepository = mock(AIQuotaUsageRepository.class);
        AIQuotaConfigRepository quotaConfigRepository = mock(AIQuotaConfigRepository.class);
        AIAuditLogRepository auditLogRepository = mock(AIAuditLogRepository.class);

        ReflectionTestUtils.setField(service, "pythonSmartBIClient", pythonClient);
        ReflectionTestUtils.setField(service, "processingService", processingService);
        ReflectionTestUtils.setField(service, "quotaUsageRepository", quotaUsageRepository);
        ReflectionTestUtils.setField(service, "quotaConfigRepository", quotaConfigRepository);
        ReflectionTestUtils.setField(service, "auditLogRepository", auditLogRepository);

        when(processingService.getTimeRangeBatchesCostAnalysis(
                eq("FACTORY-SSE"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(Map.of(
                        "batchNumber", "B-1",
                        "costSummary", Map.of("totalCost", new BigDecimal("12.50")),
                        "createdAt", LocalDateTime.of(2026, 7, 2, 10, 0))));
        when(quotaUsageRepository.findByFactoryIdAndWeekStart(
                eq("FACTORY-SSE"), any(LocalDate.class)))
                .thenReturn(Optional.of(AIQuotaUsage.builder()
                        .factoryId("FACTORY-SSE")
                        .usedCount(0)
                        .quotaLimit(100)
                        .build()));
        when(quotaConfigRepository.findByFactoryIdAndQuestionType(
                anyString(), eq("time_range")))
                .thenReturn(List.of(AIQuotaConfig.builder()
                        .factoryId("FACTORY-SSE")
                        .questionType("time_range")
                        .quotaCost(1)
                        .build()));

        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.setAttribute("role", "finance_manager");
        return new Fixture(
                service,
                pythonClient,
                quotaUsageRepository,
                auditLogRepository,
                httpRequest);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> earlyPayloads(SseEmitter emitter) throws Exception {
        Field attemptsField = ResponseBodyEmitter.class.getDeclaredField("earlySendAttempts");
        attemptsField.setAccessible(true);
        var attempts = (Iterable<ResponseBodyEmitter.DataWithMediaType>) attemptsField.get(emitter);
        java.util.ArrayList<Map<String, Object>> payloads = new java.util.ArrayList<>();
        for (ResponseBodyEmitter.DataWithMediaType attempt : attempts) {
            if (attempt.getData() instanceof Map<?, ?> map) {
                payloads.add((Map<String, Object>) map);
            }
        }
        return payloads;
    }

    private record Fixture(
            AIEnterpriseService service,
            PythonSmartBIClient pythonClient,
            AIQuotaUsageRepository quotaUsageRepository,
            AIAuditLogRepository auditLogRepository,
            MockHttpServletRequest httpRequest) {
    }
}
