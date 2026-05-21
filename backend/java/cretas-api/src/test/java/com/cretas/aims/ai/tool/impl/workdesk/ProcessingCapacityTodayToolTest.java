package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.repository.ProductionBatchRepository;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProcessingCapacityTodayTool} (Sprint 8 P1).
 */
@ExtendWith(MockitoExtension.class)
class ProcessingCapacityTodayToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private ProcessingCapacityTodayTool tool;

    @Mock
    private ProductionBatchRepository productionBatchRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-PCT-01: Tool metadata")
    void metadata() {
        assertEquals("processing_capacity_today", tool.getToolName());
        assertTrue(tool.getDescription().contains("产能"));
        assertTrue(tool.getRequiredParameters().isEmpty());
    }

    @Test
    @DisplayName("UT-PCT-02: aggregates today batches — counts + sums")
    @SuppressWarnings("unchecked")
    void aggregatesBatches() throws Exception {
        ProductionBatch b1 = batch(1L, ProductionBatchStatus.IN_PROGRESS,
                new BigDecimal("100"), new BigDecimal("50"), new BigDecimal("45"));
        ProductionBatch b2 = batch(2L, ProductionBatchStatus.COMPLETED,
                new BigDecimal("200"), new BigDecimal("200"), new BigDecimal("190"));
        ProductionBatch b3 = batch(3L, ProductionBatchStatus.PLANNED,
                new BigDecimal("80"), null, null);
        when(productionBatchRepository.findByFactoryIdAndCreatedAtBetween(
                anyString(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(b1, b2, b3));

        Map<String, Object> result = invokeDoExecute(tool, FACTORY_ID, Map.of(), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        Map<String, Object> summary = (Map<String, Object>) data.get("summary");

        assertEquals(3, summary.get("batchCount"));
        assertEquals(1, summary.get("inProgressCount"));
        assertEquals(1, summary.get("completedCount"));
        assertEquals(1, summary.get("plannedCount"));
        // 100 + 200 + 80 = 380
        assertEquals(0, ((BigDecimal) summary.get("totalPlannedQuantity"))
                .compareTo(new BigDecimal("380")));
        // 50 + 200 = 250 (b3 actual is null)
        assertEquals(0, ((BigDecimal) summary.get("totalActualQuantity"))
                .compareTo(new BigDecimal("250")));

        List<Map<String, Object>> batches = (List<Map<String, Object>>) data.get("batches");
        assertEquals(3, batches.size());
    }

    @Test
    @DisplayName("UT-PCT-03: empty list — returns 0 counts + 暂无生产 message")
    @SuppressWarnings("unchecked")
    void emptyList() throws Exception {
        when(productionBatchRepository.findByFactoryIdAndCreatedAtBetween(
                anyString(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of());

        Map<String, Object> result = invokeDoExecute(tool, FACTORY_ID, Map.of(), ctx());
        assertTrue(result.get("message").toString().contains("暂无生产"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        Map<String, Object> summary = (Map<String, Object>) data.get("summary");
        assertEquals(0, summary.get("batchCount"));
    }

    // ── helpers ──
    private ProductionBatch batch(Long id, ProductionBatchStatus status,
            BigDecimal planned, BigDecimal actual, BigDecimal good) {
        return ProductionBatch.builder()
                .id(id).factoryId(FACTORY_ID).batchNumber("PB-" + id)
                .status(status)
                .plannedQuantity(planned)
                .actualQuantity(actual)
                .goodQuantity(good)
                .build();
    }

    private Map<String, Object> ctx() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", FACTORY_ID);
        ctx.put("userId", 1L);
        return ctx;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeDoExecute(Object tool, String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        var method = findMethod(tool.getClass(), "doExecute");
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
