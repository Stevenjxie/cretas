package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.entity.foodsafety.SsopExecutionRecord;
import com.cretas.aims.entity.foodsafety.SsopProcedure;
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
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Unit tests for {@link SsopExecutionTodayTool} (Sprint 9 P2.E Phase B). */
@ExtendWith(MockitoExtension.class)
class SsopExecutionTodayToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private SsopExecutionTodayTool tool;

    @Mock
    private SsopExecutionRecordRepository recordRepository;

    @Mock
    private SsopProcedureRepository procedureRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-SET-01: metadata + READ + low risk")
    void metadata() {
        assertEquals("ssop_execution_today", tool.getToolName());
        assertFalse(tool.supportsPreview());
    }

    @Test
    @DisplayName("UT-SET-02: empty list → totalTasks=0, canStartProduction=true (vacuous)")
    @SuppressWarnings("unchecked")
    void emptyList() throws Exception {
        when(recordRepository.findByFactoryIdAndExecutionDateOrderByShiftAscIdAsc(any(), any()))
                .thenReturn(Collections.emptyList());

        Map<String, Object> result = invoke("doExecute", Map.of());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(0, data.get("totalTasks"));
        assertEquals(true, data.get("canStartProduction"));
    }

    @Test
    @DisplayName("UT-SET-03: mix of statuses → correct grouping + completionRate")
    @SuppressWarnings("unchecked")
    void mixStatuses() throws Exception {
        SsopExecutionRecord r1 = SsopExecutionRecord.builder()
                .id(1L).factoryId(FACTORY_ID).procedureId(100L)
                .shift("MORNING").status("COMPLETED").build();
        SsopExecutionRecord r2 = SsopExecutionRecord.builder()
                .id(2L).factoryId(FACTORY_ID).procedureId(100L)
                .shift("MORNING").status("SCHEDULED").build();
        SsopExecutionRecord r3 = SsopExecutionRecord.builder()
                .id(3L).factoryId(FACTORY_ID).procedureId(100L)
                .shift("NIGHT").status("FAILED").build();
        SsopExecutionRecord r4 = SsopExecutionRecord.builder()
                .id(4L).factoryId(FACTORY_ID).procedureId(100L)
                .shift("AFTERNOON").status("SKIPPED").build();

        when(recordRepository.findByFactoryIdAndExecutionDateOrderByShiftAscIdAsc(any(), any()))
                .thenReturn(List.of(r1, r2, r3, r4));
        when(procedureRepository.findById(100L))
                .thenReturn(Optional.of(SsopProcedure.builder()
                        .id(100L).name("test").procedureCode("T-1")
                        .target("EQUIPMENT").inspectorRole("quality_mgr").build()));

        Map<String, Object> result = invoke("doExecute", Map.of());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(4, data.get("totalTasks"));
        assertEquals(1, data.get("completedCount"));
        assertEquals(1, data.get("scheduledCount"));
        assertEquals(1, data.get("failedCount"));
        assertEquals(1, data.get("skippedCount"));
        // canStartProduction = false 因为有 SCHEDULED + FAILED
        assertEquals(false, data.get("canStartProduction"));
        // completionRate = (1 + 1) / 4 = 50.0
        assertEquals(50.0, data.get("completionRate"));
    }

    @Test
    @DisplayName("UT-SET-04: all COMPLETED → canStartProduction=true, completionRate=100")
    @SuppressWarnings("unchecked")
    void allCompleted() throws Exception {
        SsopExecutionRecord r1 = SsopExecutionRecord.builder()
                .id(1L).factoryId(FACTORY_ID).procedureId(100L)
                .shift("MORNING").status("COMPLETED").build();
        SsopExecutionRecord r2 = SsopExecutionRecord.builder()
                .id(2L).factoryId(FACTORY_ID).procedureId(100L)
                .shift("AFTERNOON").status("COMPLETED").build();

        when(recordRepository.findByFactoryIdAndExecutionDateOrderByShiftAscIdAsc(any(), any()))
                .thenReturn(List.of(r1, r2));
        when(procedureRepository.findById(100L))
                .thenReturn(Optional.of(SsopProcedure.builder()
                        .id(100L).name("test").procedureCode("T-1").build()));

        Map<String, Object> result = invoke("doExecute", Map.of());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(true, data.get("canStartProduction"));
        assertEquals(100.0, data.get("completionRate"));
    }

    @Test
    @DisplayName("UT-SET-05: date param honored when provided")
    @SuppressWarnings("unchecked")
    void dateParam() throws Exception {
        when(recordRepository.findByFactoryIdAndExecutionDateOrderByShiftAscIdAsc(
                eq(FACTORY_ID), eq(LocalDate.of(2026, 5, 15))))
                .thenReturn(Collections.emptyList());

        Map<String, Object> result = invoke("doExecute", Map.of("date", "2026-05-15"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals("2026-05-15", data.get("date"));
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
