package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Unit tests for {@link PriceHistoryQueryTool} (Sprint 8 P4b). */
@ExtendWith(MockitoExtension.class)
class PriceHistoryQueryToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private PriceHistoryQueryTool tool;

    @Mock
    private PurchaseOrderItemRepository purchaseOrderItemRepository;

    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-PHQ-01: metadata")
    void metadata() {
        assertEquals("price_history_query", tool.getToolName());
        assertTrue(tool.getDescription().contains("价格"));
    }

    @Test
    @DisplayName("UT-PHQ-02: no items returns empty")
    @SuppressWarnings("unchecked")
    void noHistory() throws Exception {
        when(purchaseOrderItemRepository.findByMaterialTypeId(anyString()))
                .thenReturn(List.of());

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("materialTypeId", "M001"), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<?> monthly = (List<?>) data.get("monthlyPrices");
        assertTrue(monthly.isEmpty());
    }

    @Test
    @DisplayName("UT-PHQ-03: weighted avg correctly computed for single month")
    @SuppressWarnings("unchecked")
    void weightedAvgSingleMonth() throws Exception {
        PurchaseOrderItem item1 = new PurchaseOrderItem();
        item1.setPurchaseOrderId("PO-1");
        item1.setMaterialName("猪肉");
        item1.setUnit("kg");
        item1.setQuantity(new BigDecimal("100"));
        item1.setUnitPrice(new BigDecimal("20"));

        PurchaseOrderItem item2 = new PurchaseOrderItem();
        item2.setPurchaseOrderId("PO-2");
        item2.setMaterialName("猪肉");
        item2.setUnit("kg");
        item2.setQuantity(new BigDecimal("50"));
        item2.setUnitPrice(new BigDecimal("30"));

        when(purchaseOrderItemRepository.findByMaterialTypeId("M001"))
                .thenReturn(List.of(item1, item2));

        // Both items same month
        PurchaseOrder po = new PurchaseOrder();
        po.setId("PO-1");
        po.setFactoryId(FACTORY_ID);
        po.setOrderDate(LocalDate.now().minusDays(5));
        when(purchaseOrderRepository.findById("PO-1")).thenReturn(Optional.of(po));

        PurchaseOrder po2 = new PurchaseOrder();
        po2.setId("PO-2");
        po2.setFactoryId(FACTORY_ID);
        po2.setOrderDate(LocalDate.now().minusDays(3));
        when(purchaseOrderRepository.findById("PO-2")).thenReturn(Optional.of(po2));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("materialTypeId", "M001", "monthsBack", 1), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<Map<String, Object>> monthly = (List<Map<String, Object>>) data.get("monthlyPrices");
        assertEquals(1, monthly.size());
        // weighted avg = (100*20 + 50*30) / 150 = 3500 / 150 = 23.3333
        BigDecimal wAvg = (BigDecimal) monthly.get(0).get("weightedAvgPrice");
        assertEquals(0, wAvg.compareTo(new BigDecimal("23.3333")),
                "Expected 23.3333, got " + wAvg);
    }

    @Test
    @DisplayName("UT-PHQ-04: PriceSensitive (unitPrice null) → itemsWithoutPriceVisibility")
    @SuppressWarnings("unchecked")
    void priceSensitiveStripped() throws Exception {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setPurchaseOrderId("PO-1");
        item.setMaterialName("猪肉");
        item.setUnit("kg");
        item.setQuantity(new BigDecimal("100"));
        item.setUnitPrice(null); // Stripped by @PriceSensitive

        when(purchaseOrderItemRepository.findByMaterialTypeId("M001"))
                .thenReturn(List.of(item));

        PurchaseOrder po = new PurchaseOrder();
        po.setId("PO-1");
        po.setFactoryId(FACTORY_ID);
        po.setOrderDate(LocalDate.now().minusDays(5));
        when(purchaseOrderRepository.findById("PO-1")).thenReturn(Optional.of(po));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("materialTypeId", "M001"), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(1, data.get("totalItemsInWindow"));
        assertEquals(1, data.get("itemsWithoutPriceVisibility"));
        List<?> monthly = (List<?>) data.get("monthlyPrices");
        assertTrue(monthly.isEmpty(), "All stripped items should produce no monthly buckets");
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
