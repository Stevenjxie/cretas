package com.cretas.aims.service;

import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.dto.AIResponseDTO;
import com.cretas.aims.dto.python.PythonGeneralAnalysisRequest;
import com.cretas.aims.dto.python.PythonGeneralAnalysisResponse;
import com.cretas.aims.dto.python.PythonServiceHealthResponse;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.BatchWorkSessionRepository;
import com.cretas.aims.repository.EmployeeWorkSessionRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.repository.TimeClockRecordRepository;
import com.cretas.aims.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AIAnalysisServicePythonClientTest {

    private AIAnalysisService service;
    private PythonSmartBIClient pythonClient;
    private UserRepository userRepository;
    private TimeClockRecordRepository timeClockRecordRepository;
    private EmployeeWorkSessionRepository employeeWorkSessionRepository;
    private BatchWorkSessionRepository batchWorkSessionRepository;
    private QualityInspectionRepository qualityInspectionRepository;

    @BeforeEach
    void setUp() {
        service = new AIAnalysisService();
        pythonClient = mock(PythonSmartBIClient.class);
        userRepository = mock(UserRepository.class);
        timeClockRecordRepository = mock(TimeClockRecordRepository.class);
        employeeWorkSessionRepository = mock(EmployeeWorkSessionRepository.class);
        batchWorkSessionRepository = mock(BatchWorkSessionRepository.class);
        qualityInspectionRepository = mock(QualityInspectionRepository.class);

        DashScopeConfig dashScopeConfig = mock(DashScopeConfig.class);
        when(dashScopeConfig.isAvailable()).thenReturn(false);
        ReflectionTestUtils.setField(service, "dashScopeConfig", dashScopeConfig);
        ReflectionTestUtils.setField(service, "pythonSmartBIClient", pythonClient);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "timeClockRecordRepository", timeClockRecordRepository);
        ReflectionTestUtils.setField(service, "employeeWorkSessionRepository", employeeWorkSessionRepository);
        ReflectionTestUtils.setField(service, "batchWorkSessionRepository", batchWorkSessionRepository);
        ReflectionTestUtils.setField(service, "qualityInspectionRepository", qualityInspectionRepository);
    }

    @Test
    void costAnalysisForwardsTrustedFactoryAndInteractiveUser() throws Exception {
        when(pythonClient.analyzeGeneral(anyString(), anyString(), any()))
                .thenReturn(PythonGeneralAnalysisResponse.builder()
                        .success(true)
                        .answer("cost answer")
                        .sessionId("cost-session")
                        .messageCount(3)
                        .build());

        Map<String, Object> result = service.analyzeCost(
                "FACTORY-COST", 42L, "batch-7", new HashMap<>(),
                "cost-session", "analyze this batch", true, 50);

        assertEquals(true, result.get("success"));
        assertEquals("cost answer", result.get("aiAnalysis"));
        ArgumentCaptor<PythonGeneralAnalysisRequest> requestCaptor =
                ArgumentCaptor.forClass(PythonGeneralAnalysisRequest.class);
        verify(pythonClient).analyzeGeneral(
                eq("FACTORY-COST"), eq("42"), requestCaptor.capture());
        assertEquals("analyze this batch", requestCaptor.getValue().getMessage());
        assertEquals(Boolean.FALSE, requestCaptor.getValue().getAllowTenantDataFallback());
    }

    @Test
    void costAnalysisDoesNotExposeUpstreamDetails() throws Exception {
        when(pythonClient.analyzeGeneral(anyString(), isNull(), any()))
                .thenThrow(new IOException("http://python.internal/secret?token=abc"));

        Map<String, Object> result = service.analyzeCost(
                "FACTORY-COST", "batch-8", new HashMap<>(),
                null, "question", true, 50);

        assertEquals(false, result.get("success"));
        assertEquals("AI服务暂时不可用，请稍后重试", result.get("error"));
        assertFalse(result.containsKey("errorDetail"));
        assertFalse(result.toString().contains("python.internal"));
    }

    @Test
    void employeeAnalysisForwardsRequesterIdentityInsteadOfPuttingItInBody() throws Exception {
        User employee = new User();
        employee.setId(5L);
        employee.setFactoryId("FACTORY-EMP");
        employee.setUsername("employee-5");
        employee.setFullName("Employee Five");
        employee.setDepartment("processing");
        employee.setRoleCode("operator");

        when(userRepository.findById(5L)).thenReturn(Optional.of(employee));
        when(timeClockRecordRepository.findByFactoryIdAndUserIdAndClockDateBetween(
                eq("FACTORY-EMP"), eq(5L), any(), any())).thenReturn(List.of());
        when(employeeWorkSessionRepository.sumActualWorkMinutesByUserIdAndTimeRange(
                eq(5L), any(), any())).thenReturn(0);
        when(employeeWorkSessionRepository.countByUserIdAndTimeRange(
                eq(5L), any(), any())).thenReturn(0L);
        when(batchWorkSessionRepository.countDistinctBatchesByEmployeeAndTimeRange(
                eq(5L), any(), any())).thenReturn(0L);
        when(batchWorkSessionRepository.sumWorkMinutesByEmployeeAndTimeRange(
                eq(5L), any(), any())).thenReturn(0);
        when(batchWorkSessionRepository.countCompletedByEmployeeAndTimeRange(
                eq(5L), any(), any())).thenReturn(0L);
        when(batchWorkSessionRepository.findByEmployeeIdAndTimeRange(
                eq(5L), any(), any())).thenReturn(List.of());
        when(qualityInspectionRepository.countByInspectorIdAndDateRange(
                eq(5L), any(), any())).thenReturn(0L);
        when(qualityInspectionRepository.countPassedByInspectorIdAndDateRange(
                eq(5L), any(), any())).thenReturn(0L);
        when(pythonClient.analyzeGeneral(anyString(), anyString(), any()))
                .thenReturn(PythonGeneralAnalysisResponse.builder()
                        .success(true)
                        .aiAnalysis("employee answer")
                        .sessionId("employee-session")
                        .build());

        AIResponseDTO.EmployeeAnalysisResponse result = service.analyzeEmployee(
                "FACTORY-EMP", 99L, 5L, 30, null, null);

        assertEquals("employee answer", result.getAiInsight());
        ArgumentCaptor<PythonGeneralAnalysisRequest> requestCaptor =
                ArgumentCaptor.forClass(PythonGeneralAnalysisRequest.class);
        verify(pythonClient).analyzeGeneral(
                eq("FACTORY-EMP"), eq("99"), requestCaptor.capture());
        assertEquals(Boolean.FALSE, requestCaptor.getValue().getAllowTenantDataFallback());
    }

    @Test
    void healthUsesTypedHealthyStatusWithoutServiceMetadata() throws Exception {
        when(pythonClient.health()).thenReturn(
                PythonServiceHealthResponse.builder().status("healthy").build());

        Map<String, Object> result = service.healthCheck();

        assertEquals(true, result.get("available"));
        assertEquals(1, result.size());
        assertFalse(result.containsKey("serviceUrl"));
        assertFalse(result.containsKey("serviceInfo"));
    }
}
