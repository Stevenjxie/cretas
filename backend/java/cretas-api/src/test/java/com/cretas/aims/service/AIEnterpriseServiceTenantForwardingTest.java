package com.cretas.aims.service;

import com.cretas.aims.dto.MobileDTO;
import com.cretas.aims.entity.AIAnalysisResult;
import com.cretas.aims.entity.AIQuotaUsage;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.repository.AIAuditLogRepository;
import com.cretas.aims.repository.AIQuotaUsageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AIEnterpriseServiceTenantForwardingTest {

    @Test
    void existingRequesterUserIdIsForwardedToBasicAnalysisService() {
        AIEnterpriseService service = new AIEnterpriseService();
        AIAnalysisService basicAIService = mock(AIAnalysisService.class);
        ProcessingService processingService = mock(ProcessingService.class);
        AIAnalysisResultRepository analysisResultRepository =
                mock(AIAnalysisResultRepository.class);
        AIQuotaUsageRepository quotaUsageRepository = mock(AIQuotaUsageRepository.class);
        AIAuditLogRepository auditLogRepository = mock(AIAuditLogRepository.class);

        ReflectionTestUtils.setField(service, "basicAIService", basicAIService);
        ReflectionTestUtils.setField(service, "processingService", processingService);
        ReflectionTestUtils.setField(service, "analysisResultRepository", analysisResultRepository);
        ReflectionTestUtils.setField(service, "quotaUsageRepository", quotaUsageRepository);
        ReflectionTestUtils.setField(service, "auditLogRepository", auditLogRepository);

        when(analysisResultRepository
                .findFirstByFactoryIdAndBatchIdAndReportTypeOrderByCreatedAtDesc(
                        "FACTORY-1", "batch-1", "batch"))
                .thenReturn(Optional.empty());
        when(processingService.getBatchCostAnalysis("FACTORY-1", "batch-1"))
                .thenReturn(Map.of("totalCost", 12));
        when(basicAIService.analyzeCost(
                eq("FACTORY-1"), eq(77L), eq("batch-1"), anyMap(),
                isNull(), isNull(), eq(true), eq(50)))
                .thenReturn(Map.of(
                        "success", true,
                        "aiAnalysis", "answer",
                        "sessionId", "session-1",
                        "messageCount", 1));
        when(analysisResultRepository.save(any(AIAnalysisResult.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(quotaUsageRepository.findByFactoryIdAndWeekStart(
                eq("FACTORY-1"), any(LocalDate.class)))
                .thenReturn(Optional.of(AIQuotaUsage.builder()
                        .factoryId("FACTORY-1")
                        .usedCount(0)
                        .quotaLimit(100)
                        .build()));

        MobileDTO.AICostAnalysisResponse response = service.analyzeCost(
                "FACTORY-1",
                77L,
                MobileDTO.AICostAnalysisRequest.builder().batchId("batch-1").build(),
                null);

        assertTrue(response.getSuccess());
        verify(basicAIService).analyzeCost(
                eq("FACTORY-1"), eq(77L), eq("batch-1"), anyMap(),
                isNull(), isNull(), eq(true), eq(50));
    }
}
