package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.entity.foodsafety.SsopExecutionRecord;
import com.cretas.aims.entity.foodsafety.SsopProcedure;
import com.cretas.aims.repository.foodsafety.SsopBlockingGateRepository;
import com.cretas.aims.repository.foodsafety.SsopExecutionRecordRepository;
import com.cretas.aims.repository.foodsafety.SsopProcedureRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Unit tests for {@link SsopMonthlyAuditReportTool} (Sprint 9 P2.E Phase B). */
@ExtendWith(MockitoExtension.class)
class SsopMonthlyAuditReportToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private SsopMonthlyAuditReportTool tool;

    @Mock
    private SsopExecutionRecordRepository recordRepository;

    @Mock
    private SsopProcedureRepository procedureRepository;

    @Mock
    private SsopBlockingGateRepository blockingGateRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-SMR-01: metadata + READ")
    void metadata() {
        assertEquals("ssop_monthly_audit_report", tool.getToolName());
        assertFalse(tool.supportsPreview());
    }

    @Test
    @DisplayName("UT-SMR-02: empty month → completionRate=0, totalRecords=0")
    @SuppressWarnings("unchecked")
    void emptyMonth() throws Exception {
        when(procedureRepository.countByFactoryIdAndActiveTrue(any())).thenReturn(0L);
        when(recordRepository.findByFactoryIdAndExecutionDateBetween(any(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(recordRepository.countByStatusInRange(any(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(blockingGateRepository.findByFactoryIdAndBlockedAtBetween(any(), any(), any()))
                .thenReturn(Collections.emptyList());

        Map<String, Object> result = invoke("doExecute", Map.of("yearMonth", "2026-05"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(BigDecimal.ZERO, data.get("completionRate"));
        assertEquals(0L, data.get("totalRecords"));
    }

    @Test
    @DisplayName("UT-SMR-03: 80 COMPLETED + 20 SCHEDULED → completionRate=80.00")
    @SuppressWarnings("unchecked")
    void mixedStatuses() throws Exception {
        when(procedureRepository.countByFactoryIdAndActiveTrue(any())).thenReturn(10L);
        // Procedure-level records
        SsopExecutionRecord r1 = SsopExecutionRecord.builder()
                .id(1L).procedureId(100L).status("COMPLETED").build();
        when(recordRepository.findByFactoryIdAndExecutionDateBetween(any(), any(), any()))
                .thenReturn(List.of(r1));

        // status 聚合 (80 + 20 = 100)
        when(recordRepository.countByStatusInRange(any(), any(), any()))
                .thenReturn(List.of(
                        new Object[]{"COMPLETED", 80L},
                        new Object[]{"SCHEDULED", 20L}));
        when(blockingGateRepository.findByFactoryIdAndBlockedAtBetween(any(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(procedureRepository.findById(100L))
                .thenReturn(Optional.of(SsopProcedure.builder()
                        .id(100L).name("X").procedureCode("X-1").target("EQUIPMENT").build()));

        Map<String, Object> result = invoke("doExecute", Map.of("yearMonth", "2026-05"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(BigDecimal.valueOf(80.00).setScale(2), data.get("completionRate"));
        assertEquals(100L, data.get("totalRecords"));
        Map<String, Long> breakdown = (Map<String, Long>) data.get("statusBreakdown");
        assertEquals(80L, breakdown.get("COMPLETED"));
        assertEquals(20L, breakdown.get("SCHEDULED"));
    }

    @Test
    @DisplayName("UT-SMR-04: COMPLETED + SKIPPED both count toward completionRate")
    @SuppressWarnings("unchecked")
    void completedPlusSkippedCount() throws Exception {
        when(procedureRepository.countByFactoryIdAndActiveTrue(any())).thenReturn(0L);
        when(recordRepository.findByFactoryIdAndExecutionDateBetween(any(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(recordRepository.countByStatusInRange(any(), any(), any()))
                .thenReturn(List.of(
                        new Object[]{"COMPLETED", 70L},
                        new Object[]{"SKIPPED", 30L}));
        when(blockingGateRepository.findByFactoryIdAndBlockedAtBetween(any(), any(), any()))
                .thenReturn(Collections.emptyList());

        Map<String, Object> result = invoke("doExecute", Map.of("yearMonth", "2026-05"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        // (70 + 30) / 100 = 100.00
        assertEquals(BigDecimal.valueOf(100.00).setScale(2), data.get("completionRate"));
    }

    // ── helpers ──
    private Map<String, Object> ctx() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", FACTORY_ID);
        ctx.put("userId", 1L);
        return ctx;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(String name, Map<String, Object> params) throws Exception {
        Method method = findMethod(tool.getClass(), name);
        method.setAccessible(true);
        try {
            return (Map<String, Object>) method.invoke(tool, FACTORY_ID, params, ctx());
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (ite.getCause() instanceof RuntimeException re) throw re;
            if (ite.getCause() instanceof Exception ee) throw ee;
            throw ite;
        }
    }

    private Method findMethod(Class<?> clazz, String name) {
        while (clazz != null) {
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(name)) return m;
            }
            clazz = clazz.getSuperclass();
        }
        throw new IllegalArgumentException("Method not found: " + name);
    }

    private void injectField(Object target, String name, Object value) throws Exception {
        Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private Field findField(Class<?> clazz, String name) {
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new IllegalArgumentException("Field not found: " + name);
    }
}
