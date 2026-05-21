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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Unit tests for {@link FoodSampleTraceTool} (Sprint 9 P2.A). */
@ExtendWith(MockitoExtension.class)
class FoodSampleTraceToolTest {

    private static final String FACTORY_ID = "F006";
    private static final String BATCH = "B-20260521-A01";

    @InjectMocks
    private FoodSampleTraceTool tool;

    @Mock
    private FoodSampleRepository foodSampleRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    private FoodSample sample(long id, String status) {
        return FoodSample.builder()
                .id(id)
                .factoryId(FACTORY_ID)
                .batchNumber(BATCH)
                .materialBatchId("mb-1")
                .productName("卤猪蹄")
                .sampleQuantity(new BigDecimal("130"))
                .unit("g")
                .sampleTime(LocalDateTime.now().minusHours(20))
                .expireTime(LocalDateTime.now().plusHours(28))
                .storageLocation("1号冷柜")
                .retainedByUserId(1L)
                .status(status)
                .build();
    }

    @Test
    @DisplayName("UT-FST-01: empty trace → 无留样 warning message")
    @SuppressWarnings("unchecked")
    void executeEmpty() throws Exception {
        when(foodSampleRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, BATCH))
                .thenReturn(List.of());

        Map<String, Object> params = Map.of("batchNumber", BATCH);
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(0, data.get("totalCount"));
        assertEquals(false, data.get("availableForInspection"));
        assertTrue(((String) result.get("message")).contains("无留样"));
    }

    @Test
    @DisplayName("UT-FST-02: 3 samples mixed (2 RETAINED + 1 DISPOSED) — availableForInspection=true")
    @SuppressWarnings("unchecked")
    void executeMixedSamples() throws Exception {
        when(foodSampleRepository.findByFactoryIdAndBatchNumber(anyString(), anyString()))
                .thenReturn(List.of(
                        sample(1L, "RETAINED"),
                        sample(2L, "RETAINED"),
                        sample(3L, "DISPOSED")
                ));

        Map<String, Object> params = Map.of("batchNumber", BATCH);
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(3, data.get("totalCount"));
        assertEquals(2, data.get("retainedCount"));
        assertEquals(1, data.get("disposedCount"));
        assertEquals(0, data.get("inspectionCount"));
        assertEquals(true, data.get("availableForInspection"));

        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("samples");
        assertEquals(3, rows.size());
    }

    @Test
    @DisplayName("UT-FST-03: all DISPOSED — availableForInspection=false")
    @SuppressWarnings("unchecked")
    void executeAllDisposed() throws Exception {
        when(foodSampleRepository.findByFactoryIdAndBatchNumber(anyString(), anyString()))
                .thenReturn(List.of(
                        sample(1L, "DISPOSED"),
                        sample(2L, "USED_FOR_INSPECTION")
                ));

        Map<String, Object> params = Map.of("batchNumber", BATCH);
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(2, data.get("totalCount"));
        assertEquals(0, data.get("retainedCount"));
        assertEquals(false, data.get("availableForInspection"));
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
