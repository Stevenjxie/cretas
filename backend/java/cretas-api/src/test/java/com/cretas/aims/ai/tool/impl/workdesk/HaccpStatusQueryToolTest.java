package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.entity.foodsafety.HaccpMonitoringRecord;
import com.cretas.aims.repository.foodsafety.HaccpCheckpointRepository;
import com.cretas.aims.repository.foodsafety.HaccpMonitoringRecordRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/** Unit tests for {@link HaccpStatusQueryTool} (Sprint 8 P4c). */
@ExtendWith(MockitoExtension.class)
class HaccpStatusQueryToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private HaccpStatusQueryTool tool;

    @Mock
    private HaccpMonitoringRecordRepository monitoringRepository;

    @Mock
    private HaccpCheckpointRepository checkpointRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-HSQ-01: metadata — name + description includes 'HACCP'")
    void metadata() {
        assertEquals("haccp_status_query", tool.getToolName());
        assertTrue(tool.getDescription().contains("HACCP"));
        assertTrue(tool.getDescription().contains("LLM 触发"));
        assertEquals(List.of("batchNumber"), tool.getRequiredParameters());
    }

    @Test
    @DisplayName("UT-HSQ-02: no records — BLOCK_RELEASE + actionHint")
    @SuppressWarnings("unchecked")
    void noRecords() throws Exception {
        when(monitoringRepository.findByFactoryIdAndBatchNumberOrderByMonitoringTimeDesc(
                FACTORY_ID, "B-X")).thenReturn(List.of());

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("batchNumber", "B-X"), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(0, data.get("totalRecords"));
        assertEquals("BLOCK_RELEASE", data.get("releaseHint"));
        assertEquals(false, data.get("passed"));
        assertNotNull(data.get("actionHint"));
    }

    @Test
    @DisplayName("UT-HSQ-03: all records pass — RELEASE_OK + passed=true")
    @SuppressWarnings("unchecked")
    void allPass() throws Exception {
        HaccpMonitoringRecord r1 = new HaccpMonitoringRecord();
        r1.setId(1L);
        r1.setCheckpointId(10L);
        r1.setDeviation(false);
        HaccpMonitoringRecord r2 = new HaccpMonitoringRecord();
        r2.setId(2L);
        r2.setCheckpointId(10L);
        r2.setDeviation(false);
        when(monitoringRepository.findByFactoryIdAndBatchNumberOrderByMonitoringTimeDesc(
                FACTORY_ID, "B-Y")).thenReturn(List.of(r1, r2));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("batchNumber", "B-Y"), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(2, data.get("totalRecords"));
        assertEquals(0, data.get("deviationCount"));
        assertEquals("RELEASE_OK", data.get("releaseHint"));
        assertEquals(true, data.get("passed"));
    }

    // ── helpers ──
    private Map<String, Object> ctx() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", FACTORY_ID);
        ctx.put("userId", 1L);
        return ctx;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(String name, String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        var method = findMethod(tool.getClass(), name);
        method.setAccessible(true);
        try {
            return (Map<String, Object>) method.invoke(tool, factoryId, params, context);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (ite.getCause() instanceof RuntimeException re) throw re;
            if (ite.getCause() instanceof Exception ee) throw ee;
            throw ite;
        }
    }

    private java.lang.reflect.Method findMethod(Class<?> clazz, String name) {
        while (clazz != null) {
            for (var m : clazz.getDeclaredMethods()) {
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
