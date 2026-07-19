package com.cretas.aims.service;

import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.dto.AIResponseDTO;
import com.cretas.aims.dto.python.PythonGeneralAnalysisRequest;
import com.cretas.aims.dto.python.PythonGeneralAnalysisResponse;
import com.cretas.aims.dto.python.PythonServiceHealthResponse;
import com.cretas.aims.entity.TimeClockRecord;
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
import java.time.LocalDateTime;
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
        User employee = employee(5L, "FACTORY-EMP");
        when(userRepository.findByIdAndFactoryId(5L, "FACTORY-EMP")).thenReturn(Optional.of(employee));
        stubEmployeeFacts(employee, List.of(), 0, 0L, 0L, 0, 0L, 0L, 0L);
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
        assertTrue(requestCaptor.getValue().getMessage().contains("不得推测、补值"));
        assertFalse(requestCaptor.getValue().getMessage().contains("9:00"));
        assertFalse(requestCaptor.getValue().getMessage().contains("18:00"));
        verify(userRepository).findByIdAndFactoryId(5L, "FACTORY-EMP");
        verify(userRepository, never()).findById(anyLong());
    }

    @Test
    void crossTenantEmployeeFailsClosedBeforePythonCall() {
        User foreignEmployee = employee(5L, "FACTORY-OTHER");
        when(userRepository.findById(5L)).thenReturn(Optional.of(foreignEmployee));
        when(userRepository.findByIdAndFactoryId(5L, "FACTORY-EMP")).thenReturn(Optional.empty());

        RuntimeException failure = assertThrows(RuntimeException.class, () -> service.analyzeEmployee(
                "FACTORY-EMP", 99L, 5L, 30, null, null));

        assertEquals("员工AI分析失败", failure.getMessage());
        verify(userRepository).findByIdAndFactoryId(5L, "FACTORY-EMP");
        verify(userRepository, never()).findById(anyLong());
        verifyNoInteractions(pythonClient);
        verifyNoInteractions(timeClockRecordRepository, employeeWorkSessionRepository,
                batchWorkSessionRepository, qualityInspectionRepository);
    }

    @Test
    void nonexistentEmployeeFailsClosedBeforePythonCall() {
        when(userRepository.findByIdAndFactoryId(404L, "FACTORY-EMP")).thenReturn(Optional.empty());

        RuntimeException failure = assertThrows(RuntimeException.class, () -> service.analyzeEmployee(
                "FACTORY-EMP", 99L, 404L, 30, null, null));

        assertEquals("员工AI分析失败", failure.getMessage());
        verifyNoInteractions(pythonClient);
    }

    @Test
    void noDataResponseIsExplicitlyNotComputableAndHasNoRandomFallbacks() throws Exception {
        User employee = employee(5L, "FACTORY-EMP");
        when(userRepository.findByIdAndFactoryId(5L, "FACTORY-EMP")).thenReturn(Optional.of(employee));
        stubEmployeeFacts(employee, List.of(), 0, 0L, 0L, 0, 0L, 0L, 0L);
        when(pythonClient.analyzeGeneral(anyString(), anyString(), any()))
                .thenReturn(PythonGeneralAnalysisResponse.builder()
                        .success(true)
                        .aiAnalysis("仅基于事实的分析")
                        .build());

        AIResponseDTO.EmployeeAnalysisResponse first = service.analyzeEmployee(
                "FACTORY-EMP", 99L, 5L, 30, null, null);
        AIResponseDTO.EmployeeAnalysisResponse second = service.analyzeEmployee(
                "FACTORY-EMP", 99L, 5L, 30, null, null);

        assertNull(first.getTenureMonths());
        assertNull(first.getOverallScore());
        assertNull(first.getOverallGrade());
        assertNull(first.getScoreChange());
        assertNull(first.getDepartmentRankPercent());
        assertNull(first.getAttendance().getAttendanceRate());
        assertEquals(0, first.getAttendance().getRecordCount());
        assertEquals(0, first.getWorkHours().getTotalMinutes());
        assertEquals(0L, first.getWorkHours().getSessionCount());
        assertNull(first.getProduction().getOutputQuantity());
        assertNull(first.getProduction().getQualityRate());
        assertNull(first.getProduction().getProductivityRate());
        assertTrue(first.getSkills().isEmpty());
        assertTrue(first.getSuggestions().isEmpty());
        assertTrue(first.getTrends().isEmpty());
        assertNull(first.getSessionId());
        assertNull(first.getTokensUsed());
        assertTrue(first.getNotComputableMetrics().containsAll(List.of(
                "overallScore",
                "attendance.attendanceRate",
                "production.outputQuantity",
                "production.qualityRate",
                "skills",
                "suggestions",
                "trends")));
        assertEquals(first.getScoreChange(), second.getScoreChange());
        assertEquals(first.getDepartmentRankPercent(), second.getDepartmentRankPercent());
        assertEquals(first.getTrends(), second.getTrends());
        assertEquals(first.getNotComputableMetrics(), second.getNotComputableMetrics());
    }

    @Test
    void persistedAttendanceStatusesAndRealInspectionCountsRemainFacts() throws Exception {
        User employee = employee(5L, "FACTORY-EMP");
        TimeClockRecord normalAtTen = TimeClockRecord.builder()
                .factoryId("FACTORY-EMP")
                .userId(5L)
                .clockInTime(LocalDateTime.now().withHour(10))
                .attendanceStatus("NORMAL")
                .workDurationMinutes(420)
                .build();
        TimeClockRecord lateAndEarlyAtEight = TimeClockRecord.builder()
                .factoryId("FACTORY-EMP")
                .userId(5L)
                .clockInTime(LocalDateTime.now().withHour(8))
                .attendanceStatus("LATE_AND_EARLY_LEAVE")
                .workDurationMinutes(300)
                .build();
        when(userRepository.findByIdAndFactoryId(5L, "FACTORY-EMP")).thenReturn(Optional.of(employee));
        stubEmployeeFacts(employee, List.of(normalAtTen, lateAndEarlyAtEight),
                120, 2L, 3L, 180, 2L, 4L, 3L);
        when(pythonClient.analyzeGeneral(anyString(), anyString(), any()))
                .thenReturn(PythonGeneralAnalysisResponse.builder()
                        .success(true)
                        .aiAnalysis("仅基于事实的分析")
                        .sessionId("employee-session")
                        .tokensUsed(123)
                        .build());

        AIResponseDTO.EmployeeAnalysisResponse result = service.analyzeEmployee(
                "FACTORY-EMP", 99L, 5L, 30, null, null);

        assertEquals(2, result.getAttendance().getRecordCount());
        assertEquals(1, result.getAttendance().getLateCount());
        assertEquals(1, result.getAttendance().getEarlyLeaveCount());
        assertEquals(720, result.getAttendance().getClockedWorkMinutes());
        assertEquals(3, result.getProduction().getBatchCount());
        assertEquals(2L, result.getProduction().getCompletedBatches());
        assertEquals(180, result.getProduction().getBatchWorkMinutes());
        assertEquals(4L, result.getProduction().getTotalInspections());
        assertEquals(3L, result.getProduction().getPassedInspections());
        assertEquals(75.0, result.getProduction().getQualityRate());
        assertNull(result.getProduction().getScore());
        assertFalse(result.getNotComputableMetrics().contains("production.qualityRate"));
        assertEquals("employee-session", result.getSessionId());
        assertEquals(123, result.getTokensUsed());
    }

    @Test
    void invalidUpstreamAnalysisFailsClosedInsteadOfInventingInsightOrMetadata() throws Exception {
        User employee = employee(5L, "FACTORY-EMP");
        when(userRepository.findByIdAndFactoryId(5L, "FACTORY-EMP")).thenReturn(Optional.of(employee));
        stubEmployeeFacts(employee, List.of(), 0, 0L, 0L, 0, 0L, 0L, 0L);
        when(pythonClient.analyzeGeneral(anyString(), anyString(), any()))
                .thenReturn(PythonGeneralAnalysisResponse.builder().success(true).build());

        RuntimeException failure = assertThrows(RuntimeException.class, () -> service.analyzeEmployee(
                "FACTORY-EMP", 99L, 5L, 30, null, null));

        assertEquals("员工AI分析失败", failure.getMessage());
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

    private User employee(Long id, String factoryId) {
        User employee = new User();
        employee.setId(id);
        employee.setFactoryId(factoryId);
        employee.setUsername("employee-" + id);
        employee.setFullName("Employee " + id);
        employee.setDepartment("processing");
        employee.setRoleCode("operator");
        return employee;
    }

    private void stubEmployeeFacts(
            User employee,
            List<TimeClockRecord> attendanceRecords,
            Integer workSessionMinutes,
            long workSessionCount,
            long batchCount,
            Integer batchWorkMinutes,
            long completedBatches,
            long totalInspections,
            long passedInspections) {
        when(timeClockRecordRepository.findByFactoryIdAndUserIdAndClockDateBetween(
                eq(employee.getFactoryId()), eq(employee.getId()), any(), any()))
                .thenReturn(attendanceRecords);
        when(employeeWorkSessionRepository.sumActualWorkMinutesByUserIdAndTimeRange(
                eq(employee.getId()), any(), any())).thenReturn(workSessionMinutes);
        when(employeeWorkSessionRepository.countByUserIdAndTimeRange(
                eq(employee.getId()), any(), any())).thenReturn(workSessionCount);
        when(batchWorkSessionRepository.countDistinctBatchesByEmployeeAndTimeRange(
                eq(employee.getId()), any(), any())).thenReturn(batchCount);
        when(batchWorkSessionRepository.sumWorkMinutesByEmployeeAndTimeRange(
                eq(employee.getId()), any(), any())).thenReturn(batchWorkMinutes);
        when(batchWorkSessionRepository.countCompletedByEmployeeAndTimeRange(
                eq(employee.getId()), any(), any())).thenReturn(completedBatches);
        when(qualityInspectionRepository.countByInspectorIdAndDateRange(
                eq(employee.getId()), any(), any())).thenReturn(totalInspections);
        when(qualityInspectionRepository.countPassedByInspectorIdAndDateRange(
                eq(employee.getId()), any(), any())).thenReturn(passedInspections);
    }
}
