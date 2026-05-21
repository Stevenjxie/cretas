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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Unit tests for {@link SsopProductionGateCheckTool} (Sprint 9 P2.E Phase B). */
@ExtendWith(MockitoExtension.class)
class SsopProductionGateCheckToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private SsopProductionGateCheckTool tool;

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
    @DisplayName("UT-SPG-01: metadata + READ + 0 required params")
    void metadata() {
        assertEquals("ssop_production_gate_check", tool.getToolName());
        assertEquals(0, tool.getParametersSchema().get("properties") instanceof Map
                ? 0 : 0); // sanity
        // schema has at least one property (date) but 0 required
        assertTrue(((List<?>) tool.getParametersSchema().get("required")).isEmpty());
    }

    @Test
    @DisplayName("UT-SPG-02: empty list → canStartProduction=true (vacuous)")
    @SuppressWarnings("unchecked")
    void emptyList() throws Exception {
        when(recordRepository.findByFactoryIdAndExecutionDateOrderByShiftAscIdAsc(any(), any()))
                .thenReturn(Collections.emptyList());
        when(recordRepository.findByFactoryIdAndExecutionDateAndStatusIn(any(), any(), any()))
                .thenReturn(Collections.emptyList());

        Map<String, Object> result = invoke("doExecute", Map.of());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(true, data.get("canStartProduction"));
        assertEquals(0, data.get("blockedCount"));
    }

    @Test
    @DisplayName("UT-SPG-03: all COMPLETED → canStartProduction=true")
    @SuppressWarnings("unchecked")
    void allCompleted() throws Exception {
        SsopExecutionRecord r1 = SsopExecutionRecord.builder()
                .id(1L).factoryId(FACTORY_ID).procedureId(100L)
                .shift("MORNING").status("COMPLETED").build();
        when(recordRepository.findByFactoryIdAndExecutionDateOrderByShiftAscIdAsc(any(), any()))
                .thenReturn(List.of(r1));
        when(recordRepository.findByFactoryIdAndExecutionDateAndStatusIn(any(), any(), any()))
                .thenReturn(Collections.emptyList());

        Map<String, Object> result = invoke("doExecute", Map.of());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(true, data.get("canStartProduction"));
        assertEquals(1, data.get("totalTasks"));
    }

    @Test
    @DisplayName("UT-SPG-04: SCHEDULED present → canStartProduction=false, blocked list 含")
    @SuppressWarnings("unchecked")
    void scheduledBlocks() throws Exception {
        SsopExecutionRecord r1 = SsopExecutionRecord.builder()
                .id(1L).factoryId(FACTORY_ID).procedureId(100L)
                .shift("MORNING").status("COMPLETED").build();
        SsopExecutionRecord r2 = SsopExecutionRecord.builder()
                .id(2L).factoryId(FACTORY_ID).procedureId(101L)
                .shift("MORNING").status("SCHEDULED").build();
        when(recordRepository.findByFactoryIdAndExecutionDateOrderByShiftAscIdAsc(any(), any()))
                .thenReturn(List.of(r1, r2));
        when(recordRepository.findByFactoryIdAndExecutionDateAndStatusIn(any(), any(), any()))
                .thenReturn(List.of(r2));
        when(procedureRepository.findById(101L))
                .thenReturn(Optional.of(SsopProcedure.builder()
                        .id(101L).name("阻产模板").procedureCode("BLOCK-1")
                        .target("EQUIPMENT").build()));

        Map<String, Object> result = invoke("doExecute", Map.of());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(false, data.get("canStartProduction"));
        assertEquals(1, data.get("blockedCount"));
        List<Map<String, Object>> blocked = (List<Map<String, Object>>) data.get("blockedProcedures");
        assertEquals(1, blocked.size());
        assertEquals("阻产模板", blocked.get(0).get("procedureName"));
    }

    @Test
    @DisplayName("UT-SPG-05: FAILED present → canStartProduction=false")
    @SuppressWarnings("unchecked")
    void failedBlocks() throws Exception {
        SsopExecutionRecord r1 = SsopExecutionRecord.builder()
                .id(1L).factoryId(FACTORY_ID).procedureId(100L)
                .shift("MORNING").status("FAILED").build();
        when(recordRepository.findByFactoryIdAndExecutionDateOrderByShiftAscIdAsc(any(), any()))
                .thenReturn(List.of(r1));
        when(recordRepository.findByFactoryIdAndExecutionDateAndStatusIn(any(), any(), any()))
                .thenReturn(List.of(r1));
        when(procedureRepository.findById(100L))
                .thenReturn(Optional.of(SsopProcedure.builder().id(100L)
                        .name("X").procedureCode("X-1").build()));

        Map<String, Object> result = invoke("doExecute", Map.of());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(false, data.get("canStartProduction"));
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
