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
import static org.mockito.Mockito.when;

/** Unit tests for {@link MaterialDisposalRecommendationTool} (Sprint 8 P4). */
@ExtendWith(MockitoExtension.class)
class MaterialDisposalRecommendationToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private MaterialDisposalRecommendationTool tool;

    @Mock
    private MaterialBatchRepository materialBatchRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-MDR-01: metadata — READ + default 7 days")
    void metadata() {
        assertEquals("material_disposal_recommendation", tool.getToolName());
        assertTrue(tool.getDescription().contains("处置建议"));
        assertTrue(tool.getRequiredParameters().isEmpty());
    }

    @Test
    @DisplayName("UT-MDR-02: expired batch → SCRAP recommendation")
    @SuppressWarnings("unchecked")
    void expiredBatchRecommendsScrap() throws Exception {
        when(materialBatchRepository.findExpiringBatches(anyString(), any())).thenReturn(List.of());
        MaterialBatch expired = batch("b1", LocalDate.now().minusDays(3),
                new BigDecimal("50"), new BigDecimal("50"));
        when(materialBatchRepository.findExpiredBatches(anyString())).thenReturn(List.of(expired));

        Map<String, Object> result = invokeDoExecute(Map.of(), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<Map<String, Object>> recs = (List<Map<String, Object>>) data.get("recommendations");
        assertEquals(1, recs.size());
        assertEquals("SCRAP", recs.get(0).get("recommendedAction"));
        assertEquals(true, recs.get(0).get("isExpired"));
    }

    @Test
    @DisplayName("UT-MDR-03: 0-2 days + remain<30% → DISCOUNT_SELL")
    @SuppressWarnings("unchecked")
    void shortExpiryLowStockRecommendsDiscount() throws Exception {
        // total=100, used=80 → currentQuantity=20 → 20% remain
        MaterialBatch b = batch("b2", LocalDate.now().plusDays(1),
                new BigDecimal("100"), new BigDecimal("20"));
        when(materialBatchRepository.findExpiringBatches(anyString(), any())).thenReturn(List.of(b));
        when(materialBatchRepository.findExpiredBatches(anyString())).thenReturn(List.of());

        Map<String, Object> result = invokeDoExecute(Map.of(), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<Map<String, Object>> recs = (List<Map<String, Object>>) data.get("recommendations");
        assertEquals("DISCOUNT_SELL", recs.get(0).get("recommendedAction"));
    }

    @Test
    @DisplayName("UT-MDR-04: SCRAPPED status excluded from recs")
    @SuppressWarnings("unchecked")
    void scrappedExcluded() throws Exception {
        MaterialBatch b = batch("b3", LocalDate.now().plusDays(3),
                new BigDecimal("100"), new BigDecimal("80"));
        b.setStatus(MaterialBatchStatus.SCRAPPED);
        when(materialBatchRepository.findExpiringBatches(anyString(), any())).thenReturn(List.of(b));
        when(materialBatchRepository.findExpiredBatches(anyString())).thenReturn(List.of());

        Map<String, Object> result = invokeDoExecute(Map.of(), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<Map<String, Object>> recs = (List<Map<String, Object>>) data.get("recommendations");
        assertEquals(0, recs.size());
    }

    // ── helpers ──
    private MaterialBatch batch(String id, LocalDate expire,
            BigDecimal receipt, BigDecimal current) {
        MaterialBatch b = new MaterialBatch();
        b.setId(id);
        b.setFactoryId(FACTORY_ID);
        b.setBatchNumber("BN-" + id);
        b.setMaterialTypeId("m1");
        b.setReceiptDate(LocalDate.now().minusDays(10));
        b.setExpireDate(expire);
        b.setReceiptQuantity(receipt);
        b.setUsedQuantity(receipt.subtract(current));
        b.setReservedQuantity(BigDecimal.ZERO);
        b.setQuantityUnit("kg");
        b.setWarehouseId("WH-LOG");
        b.setStatus(MaterialBatchStatus.AVAILABLE);
        b.setCreatedBy(1L);
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
