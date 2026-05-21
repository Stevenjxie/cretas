package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.entity.foodsafety.HaccpCheckpoint;
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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** Unit tests for {@link HaccpCheckpointReviewTool} (Sprint 8 P3 Phase B). */
@ExtendWith(MockitoExtension.class)
class HaccpCheckpointReviewToolTest {

    private static final String FACTORY_ID = "F006";
    private static final String BATCH = "B-20260518-A03";

    @InjectMocks
    private HaccpCheckpointReviewTool tool;

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
    @DisplayName("UT-HCR-01: metadata")
    void metadata() {
        assertEquals("haccp_checkpoint_review", tool.getToolName());
        assertEquals(List.of("batchNumber"), tool.getRequiredParameters());
    }

    @Test
    @DisplayName("UT-HCR-02: all passed records → passed=true, no deviations")
    @SuppressWarnings("unchecked")
    void allPassed() throws Exception {
        HaccpCheckpoint ccp = HaccpCheckpoint.builder()
                .id(1L).checkpointCode("CCP-01").name("中心温度").hazardType("BIOLOGICAL")
                .criticalLimitMin(new BigDecimal("75"))
                .criticalLimitMax(new BigDecimal("100"))
                .unit("℃").correctiveAction("继续加热")
                .build();
        HaccpMonitoringRecord r1 = HaccpMonitoringRecord.builder()
                .id(10L).checkpointId(1L).batchNumber(BATCH)
                .monitoringTime(LocalDateTime.now())
                .measuredValue(new BigDecimal("85"))
                .operatorUserId(1L).isDeviation(false)
                .build();

        when(monitoringRepository.findByFactoryIdAndBatchNumberOrderByMonitoringTimeDesc(
                FACTORY_ID, BATCH)).thenReturn(List.of(r1));
        lenient().when(checkpointRepository.findById(1L)).thenReturn(Optional.of(ccp));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID, Map.of("batchNumber", BATCH), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(1, data.get("recordCount"));
        assertEquals(0, data.get("deviationCount"));
        assertEquals(true, data.get("passed"));
    }

    @Test
    @DisplayName("UT-HCR-03: deviation found → passed=false, deviation list populated with correctiveAction")
    @SuppressWarnings("unchecked")
    void deviationDetected() throws Exception {
        HaccpCheckpoint ccp = HaccpCheckpoint.builder()
                .id(2L).checkpointCode("CCP-02").name("冷却时间").hazardType("BIOLOGICAL")
                .criticalLimitMin(new BigDecimal("0"))
                .criticalLimitMax(new BigDecimal("90"))
                .unit("min").correctiveAction("立即重新冷却并隔离批次")
                .build();
        HaccpMonitoringRecord r1 = HaccpMonitoringRecord.builder()
                .id(11L).checkpointId(2L).batchNumber(BATCH)
                .monitoringTime(LocalDateTime.now())
                .measuredValue(new BigDecimal("120"))
                .operatorUserId(1L).isDeviation(true)
                .deviationAction("已隔离")
                .build();

        when(monitoringRepository.findByFactoryIdAndBatchNumberOrderByMonitoringTimeDesc(
                FACTORY_ID, BATCH)).thenReturn(List.of(r1));
        lenient().when(checkpointRepository.findById(2L)).thenReturn(Optional.of(ccp));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID, Map.of("batchNumber", BATCH), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(1, data.get("deviationCount"));
        assertEquals(false, data.get("passed"));
        List<Map<String, Object>> devs = (List<Map<String, Object>>) data.get("deviations");
        assertEquals("立即重新冷却并隔离批次", devs.get(0).get("correctiveAction"));
    }

    @Test
    @DisplayName("UT-HCR-04: empty records → passed=false (无 HACCP 记录无法验证)")
    @SuppressWarnings("unchecked")
    void emptyRecords() throws Exception {
        when(monitoringRepository.findByFactoryIdAndBatchNumberOrderByMonitoringTimeDesc(
                anyString(), anyString())).thenReturn(List.of());

        Map<String, Object> result = invoke("doExecute", FACTORY_ID, Map.of("batchNumber", BATCH), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(0, data.get("recordCount"));
        assertEquals(false, data.get("passed"));
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
