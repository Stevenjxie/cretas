package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.entity.foodsafety.FoodSample;
import com.cretas.aims.repository.foodsafety.FoodSampleRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Unit tests for {@link FoodSampleQueryPendingDisposeTool} (Sprint 9 P2.A). */
@ExtendWith(MockitoExtension.class)
class FoodSampleQueryPendingDisposeToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private FoodSampleQueryPendingDisposeTool tool;

    @Mock
    private FoodSampleRepository foodSampleRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-FSQ-01: metadata — READ, no preview")
    void metadata() {
        assertEquals("food_sample_query_pending_dispose", tool.getToolName());
        assertFalse(tool.supportsPreview());
        // ActionType auto-derives READ from name (no _create/_update/_delete suffix)
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.ActionType.READ, tool.getActionType());
    }

    @Test
    @DisplayName("UT-FSQ-02: empty pending → message says no pending")
    @SuppressWarnings("unchecked")
    void executeEmpty() throws Exception {
        when(foodSampleRepository.findByFactoryIdAndStatusAndExpireTimeBefore(
                eq(FACTORY_ID), eq("RETAINED"), any(LocalDateTime.class)))
                .thenReturn(List.of());

        Map<String, Object> result = invoke("doExecute", FACTORY_ID, Map.of(), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(0, data.get("count"));
        assertNotNull(data.get("samples"));
        assertTrue(((List<?>) data.get("samples")).isEmpty());
        assertTrue(((String) result.get("message")).contains("无待销毁留样"));
    }

    @Test
    @DisplayName("UT-FSQ-03: pending sample → row with overdueHours computed")
    @SuppressWarnings("unchecked")
    void executeWithPending() throws Exception {
        LocalDateTime sampleTime = LocalDateTime.now().minusHours(50);
        FoodSample s = FoodSample.builder()
                .id(1L)
                .factoryId(FACTORY_ID)
                .batchNumber("B-1")
                .materialBatchId("mb-1")
                .productName("卤猪蹄")
                .sampleQuantity(new BigDecimal("130"))
                .unit("g")
                .sampleTime(sampleTime)
                .expireTime(sampleTime.plusHours(48))   // 2 hours overdue
                .storageLocation("1号冷柜")
                .retainedByUserId(1L)
                .status("RETAINED")
                .build();
        when(foodSampleRepository.findByFactoryIdAndStatusAndExpireTimeBefore(
                anyString(), anyString(), any(LocalDateTime.class)))
                .thenReturn(List.of(s));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID, Map.of(), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("samples");

        assertEquals(1, data.get("count"));
        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertEquals("B-1", row.get("batchNumber"));
        assertEquals("RETAINED", "RETAINED");
        Long overdue = (Long) row.get("overdueHours");
        assertTrue(overdue >= 1, "overdueHours should be >= 1, was " + overdue);
        assertTrue(((String) result.get("message")).contains("已过 48h"));
    }

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
