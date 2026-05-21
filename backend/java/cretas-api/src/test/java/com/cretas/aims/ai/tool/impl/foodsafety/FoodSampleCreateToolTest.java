package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.foodsafety.FoodSample;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link FoodSampleCreateTool} (Sprint 9 P2.A). */
@ExtendWith(MockitoExtension.class)
class FoodSampleCreateToolTest {

    private static final String FACTORY_ID = "F006";
    private static final String BATCH = "B-20260521-A01";
    private static final String MATERIAL_BATCH_ID = "uuid-mb-001";

    @InjectMocks
    private FoodSampleCreateTool tool;

    @Mock
    private FoodSampleRepository foodSampleRepository;

    @Mock
    private MaterialBatchRepository materialBatchRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-FSC-01: metadata + supportsPreview + WRITE")
    void metadata() {
        assertEquals("food_sample_create", tool.getToolName());
        assertTrue(tool.supportsPreview());
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.ActionType.WRITE, tool.getActionType());
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.RiskLevel.MEDIUM, tool.getRiskLevel());
    }

    @Test
    @DisplayName("UT-FSC-02: doExecute — creates RETAINED sample with expireTime = now+48h")
    @SuppressWarnings("unchecked")
    void executeCreatesSample() throws Exception {
        when(foodSampleRepository.findByFactoryIdAndBatchNumberAndSampleTimeAfter(
                eq(FACTORY_ID), eq(BATCH), any())).thenReturn(List.of());
        MaterialBatch mb = new MaterialBatch();
        mb.setId(MATERIAL_BATCH_ID);
        when(materialBatchRepository.findById(MATERIAL_BATCH_ID)).thenReturn(Optional.of(mb));
        when(foodSampleRepository.save(any(FoodSample.class))).thenAnswer(inv -> {
            FoodSample s = inv.getArgument(0);
            s.setId(9999L);
            return s;
        });

        Map<String, Object> params = Map.of(
                "batchNumber", BATCH,
                "materialBatchId", MATERIAL_BATCH_ID,
                "productName", "卤猪蹄 200g",
                "sampleQuantity", new BigDecimal("130"),
                "storageLocation", "1号冷柜A区"
        );
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(9999L, data.get("id"));
        assertEquals(BATCH, data.get("batchNumber"));
        assertEquals("RETAINED", data.get("status"));
        assertEquals("g", data.get("unit"));
        verify(foodSampleRepository, times(1)).save(any(FoodSample.class));
        // expireTime exists
        assertNotNull(data.get("expireTime"));
        assertNotNull(data.get("sampleTime"));
    }

    @Test
    @DisplayName("UT-FSC-03: R4 dedup — 5min 内同 batch 已留样 → returns count=0 + existingId")
    @SuppressWarnings("unchecked")
    void executeDedupReturnsExisting() throws Exception {
        FoodSample existing = FoodSample.builder()
                .id(7777L)
                .factoryId(FACTORY_ID)
                .batchNumber(BATCH)
                .sampleTime(LocalDateTime.now().minusMinutes(2))
                .build();
        when(foodSampleRepository.findByFactoryIdAndBatchNumberAndSampleTimeAfter(
                eq(FACTORY_ID), eq(BATCH), any())).thenReturn(List.of(existing));

        Map<String, Object> params = Map.of(
                "batchNumber", BATCH,
                "materialBatchId", MATERIAL_BATCH_ID,
                "productName", "卤猪蹄",
                "sampleQuantity", new BigDecimal("130"),
                "storageLocation", "1号冷柜"
        );
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals("DUPLICATE", data.get("status"));
        assertEquals(7777L, data.get("existingId"));
        assertEquals(0, data.get("count"));
        verify(foodSampleRepository, never()).save(any());
    }

    @Test
    @DisplayName("UT-FSC-04: MaterialBatch not found → BusinessException 404")
    void executeMaterialBatchNotFound() {
        when(foodSampleRepository.findByFactoryIdAndBatchNumberAndSampleTimeAfter(
                anyString(), anyString(), any())).thenReturn(List.of());
        when(materialBatchRepository.findById(anyString())).thenReturn(Optional.empty());

        Map<String, Object> params = Map.of(
                "batchNumber", BATCH,
                "materialBatchId", "missing-uuid",
                "productName", "卤猪蹄",
                "sampleQuantity", new BigDecimal("130"),
                "storageLocation", "1号冷柜"
        );
        BusinessException ex = assertThrows(BusinessException.class,
                () -> invoke("doExecute", FACTORY_ID, params, ctx()));
        assertEquals(404, ex.getCode());
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
