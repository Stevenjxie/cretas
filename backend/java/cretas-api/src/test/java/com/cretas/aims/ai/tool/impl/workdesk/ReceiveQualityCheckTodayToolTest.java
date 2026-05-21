package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
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
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Unit tests for {@link ReceiveQualityCheckTodayTool} (Sprint 8 P4). */
@ExtendWith(MockitoExtension.class)
class ReceiveQualityCheckTodayToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private ReceiveQualityCheckTodayTool tool;

    @Mock
    private MaterialBatchRepository materialBatchRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-RQC-01: metadata — READ no required params")
    void metadata() {
        assertEquals("receive_quality_check_today", tool.getToolName());
        assertTrue(tool.getRequiredParameters().isEmpty());
    }

    @Test
    @DisplayName("UT-RQC-02: INSPECTING batch returned with daysWaiting")
    @SuppressWarnings("unchecked")
    void inspectingBatchesReturned() throws Exception {
        MaterialBatch b = batch("b1", MaterialBatchStatus.INSPECTING);
        when(materialBatchRepository
                .findByFactoryIdAndStatus(anyString(), eq(MaterialBatchStatus.INSPECTING)))
                .thenReturn(List.of(b));
        when(materialBatchRepository
                .countByFactoryIdAndReceiptDateAfter(anyString(), any()))
                .thenReturn(3L);

        Map<String, Object> result = invokeDoExecute(Map.of(), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(1, data.get("inspectingCount"));
        assertEquals(3L, data.get("todayInboundCount"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("inspectingBatches");
        assertEquals("b1", rows.get(0).get("batchId"));
        assertEquals("INSPECTING", rows.get(0).get("status"));
    }

    @Test
    @DisplayName("UT-RQC-03: no batches — friendly empty message")
    @SuppressWarnings("unchecked")
    void noBatchesEmptyMessage() throws Exception {
        when(materialBatchRepository
                .findByFactoryIdAndStatus(anyString(), eq(MaterialBatchStatus.INSPECTING)))
                .thenReturn(List.of());
        when(materialBatchRepository
                .countByFactoryIdAndReceiptDateAfter(anyString(), any()))
                .thenReturn(0L);

        Map<String, Object> result = invokeDoExecute(Map.of(), ctx());
        assertTrue(result.get("message").toString().contains("无"));
    }

    // ── helpers ──
    private MaterialBatch batch(String id, MaterialBatchStatus status) {
        MaterialBatch b = new MaterialBatch();
        b.setId(id);
        b.setFactoryId(FACTORY_ID);
        b.setBatchNumber("BN-" + id);
        b.setMaterialTypeId("m1");
        b.setReceiptDate(LocalDate.now());
        b.setReceiptQuantity(new BigDecimal("50"));
        b.setUsedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(BigDecimal.ZERO);
        b.setQuantityUnit("kg");
        b.setWarehouseId("WH-LOG");
        b.setStatus(status);
        b.setCreatedBy(1L);
        b.setCreatedAt(java.time.LocalDateTime.now().minusDays(1));
        return b;
    }

    private Map<String, Object> ctx() {
        Map<String, Object> c = new HashMap<>();
        c.put("factoryId", FACTORY_ID);
        c.put("userId", 1L);
        return c;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeDoExecute(Map<String, Object> params,
            Map<String, Object> context) throws Exception {
        var m = findMethod(tool.getClass(), "doExecute");
        m.setAccessible(true);
        try {
            return (Map<String, Object>) m.invoke(tool, FACTORY_ID, params, context);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (ite.getCause() instanceof RuntimeException re) throw re;
            if (ite.getCause() instanceof Exception ee) throw ee;
            throw ite;
        }
    }

    private java.lang.reflect.Method findMethod(Class<?> c, String n) {
        while (c != null) {
            for (var m : c.getDeclaredMethods()) if (m.getName().equals(n)) return m;
            c = c.getSuperclass();
        }
        throw new IllegalArgumentException("not found: " + n);
    }

    private void injectField(Object t, String n, Object v) throws Exception {
        Field f = findField(t.getClass(), n);
        f.setAccessible(true);
        f.set(t, v);
    }

    private Field findField(Class<?> c, String n) {
        while (c != null) {
            try { return c.getDeclaredField(n); }
            catch (NoSuchFieldException e) { c = c.getSuperclass(); }
        }
        throw new IllegalArgumentException("field not found: " + n);
    }
}
