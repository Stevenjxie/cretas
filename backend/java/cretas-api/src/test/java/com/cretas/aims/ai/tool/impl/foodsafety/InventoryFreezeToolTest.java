package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link InventoryFreezeTool} (Sprint 8 P3 Phase B). */
@ExtendWith(MockitoExtension.class)
class InventoryFreezeToolTest {

    private static final String FACTORY_ID = "F006";
    private static final String BATCH = "B-20260518-A03";

    @InjectMocks
    private InventoryFreezeTool tool;

    @Mock
    private MaterialBatchRepository materialBatchRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-IF-01: metadata + supportsPreview + WRITE")
    void metadata() {
        assertEquals("inventory_freeze", tool.getToolName());
        assertTrue(tool.supportsPreview());
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.ActionType.WRITE, tool.getActionType());
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.RiskLevel.HIGH, tool.getRiskLevel());
    }

    @Test
    @DisplayName("UT-IF-02: doPreview — AVAILABLE batch → canDo=true + severity=BLOCKING")
    @SuppressWarnings("unchecked")
    void previewAllowsFreeze() throws Exception {
        MaterialBatch mb = new MaterialBatch();
        mb.setBatchNumber(BATCH);
        mb.setMaterialTypeId("MT-001");
        mb.setStatus(MaterialBatchStatus.AVAILABLE);
        mb.setReceiptQuantity(new BigDecimal("50"));
        mb.setQuantityUnit("kg");
        when(materialBatchRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, BATCH))
                .thenReturn(Optional.of(mb));

        Map<String, Object> params = Map.of("batchNumber", BATCH, "reason", "客户投诉拉肚子");
        Map<String, Object> result = invoke("doPreview", FACTORY_ID, params, ctx());

        assertEquals("PREVIEW", result.get("status"));
        assertEquals("BLOCKING", result.get("severity"));
        assertEquals(true, result.get("canDo"));
        assertEquals("AVAILABLE", result.get("currentStatus"));
    }

    @Test
    @DisplayName("UT-IF-03: doPreview — already FROZEN → status=ALREADY_FROZEN, canDo=false")
    @SuppressWarnings("unchecked")
    void previewAlreadyFrozen() throws Exception {
        MaterialBatch mb = new MaterialBatch();
        mb.setBatchNumber(BATCH);
        mb.setStatus(MaterialBatchStatus.FROZEN);
        mb.setReceiptQuantity(new BigDecimal("50"));
        mb.setQuantityUnit("kg");
        when(materialBatchRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, BATCH))
                .thenReturn(Optional.of(mb));

        Map<String, Object> params = Map.of("batchNumber", BATCH, "reason", "已冻结再次尝试");
        Map<String, Object> result = invoke("doPreview", FACTORY_ID, params, ctx());

        assertEquals("ALREADY_FROZEN", result.get("status"));
        assertEquals(false, result.get("canDo"));
    }

    @Test
    @DisplayName("UT-IF-04: doExecute — AVAILABLE → FROZEN, save called, notes appended")
    @SuppressWarnings("unchecked")
    void executeFreezes() throws Exception {
        MaterialBatch mb = new MaterialBatch();
        mb.setBatchNumber(BATCH);
        mb.setStatus(MaterialBatchStatus.AVAILABLE);
        mb.setReceiptQuantity(new BigDecimal("50"));
        mb.setQuantityUnit("kg");
        when(materialBatchRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, BATCH))
                .thenReturn(Optional.of(mb));

        Map<String, Object> params = Map.of("batchNumber", BATCH, "reason", "召回");
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals("FROZEN", data.get("currentStatus"));
        assertEquals("AVAILABLE", data.get("previousStatus"));
        verify(materialBatchRepository, times(1)).save(any(MaterialBatch.class));
        // 状态确实改了
        assertEquals(MaterialBatchStatus.FROZEN, mb.getStatus());
        // notes 含 audit line
        assertNotNull(mb.getNotes());
        assertTrue(mb.getNotes().contains("FREEZE"));
    }

    @Test
    @DisplayName("UT-IF-05: doExecute — already FROZEN → status=ALREADY_FROZEN, NO save")
    @SuppressWarnings("unchecked")
    void executeAlreadyFrozen() throws Exception {
        MaterialBatch mb = new MaterialBatch();
        mb.setBatchNumber(BATCH);
        mb.setStatus(MaterialBatchStatus.FROZEN);
        when(materialBatchRepository.findByFactoryIdAndBatchNumber(FACTORY_ID, BATCH))
                .thenReturn(Optional.of(mb));

        Map<String, Object> params = Map.of("batchNumber", BATCH, "reason", "重复");
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());

        assertEquals("ALREADY_FROZEN", result.get("status"));
        verify(materialBatchRepository, never()).save(any());
    }

    @Test
    @DisplayName("UT-IF-06: doExecute — batch not found → BusinessException 404")
    void executeNotFoundThrows() {
        when(materialBatchRepository.findByFactoryIdAndBatchNumber(anyString(), anyString()))
                .thenReturn(Optional.empty());
        Map<String, Object> params = Map.of("batchNumber", "B-MISSING", "reason", "X");
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
