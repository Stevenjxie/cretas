package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository;
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
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** Unit tests for {@link SupplierDeliveryEtaTool} (Sprint 8 P4b). */
@ExtendWith(MockitoExtension.class)
class SupplierDeliveryEtaToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private SupplierDeliveryEtaTool tool;

    @Mock
    private SupplierRepository supplierRepository;

    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;

    @Mock
    private PurchaseOrderItemRepository purchaseOrderItemRepository;

    @Mock
    private PurchaseReceiveRecordRepository purchaseReceiveRecordRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-SDE-01: metadata")
    void metadata() {
        assertEquals("supplier_delivery_eta", tool.getToolName());
        assertTrue(tool.getDescription().contains("交期"));
    }

    @Test
    @DisplayName("UT-SDE-02: nominalDeliveryDays when no history")
    @SuppressWarnings("unchecked")
    void nominalOnly() throws Exception {
        Supplier sup = new Supplier();
        sup.setId("SUP-1");
        sup.setFactoryId(FACTORY_ID);
        sup.setName("供应商A");
        sup.setDeliveryDays(5);
        when(supplierRepository.findByFactoryId(FACTORY_ID)).thenReturn(List.of(sup));
        when(purchaseOrderRepository.findByFactoryIdAndSupplierId(anyString(), anyString()))
                .thenReturn(List.of());

        Map<String, Object> result = invoke("doExecute", FACTORY_ID, Map.of(), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<Map<String, Object>> etaList = (List<Map<String, Object>>) data.get("eta");
        assertEquals(1, etaList.size());
        Map<String, Object> e = etaList.get(0);
        assertEquals(5, e.get("nominalDeliveryDays"));
        assertNull(e.get("historicalAvgDays"));
        assertEquals(5, e.get("recommendedEtaDays"));
    }

    @Test
    @DisplayName("UT-SDE-03: recommended = MAX(historical, nominal) — historical higher")
    @SuppressWarnings("unchecked")
    void historicalHigherThanNominal() throws Exception {
        Supplier sup = new Supplier();
        sup.setId("SUP-1");
        sup.setFactoryId(FACTORY_ID);
        sup.setName("供应商A");
        sup.setDeliveryDays(5);
        when(supplierRepository.findByFactoryId(FACTORY_ID)).thenReturn(List.of(sup));

        PurchaseOrder po = new PurchaseOrder();
        po.setId("PO-1");
        po.setFactoryId(FACTORY_ID);
        po.setSupplierId("SUP-1");
        po.setOrderDate(LocalDate.now().minusDays(20));
        when(purchaseOrderRepository.findByFactoryIdAndSupplierId(FACTORY_ID, "SUP-1"))
                .thenReturn(List.of(po));

        PurchaseReceiveRecord r = new PurchaseReceiveRecord();
        r.setPurchaseOrderId("PO-1");
        // ReceiveDate 比 orderDate 晚 8 天 (historical AVG = 8)
        r.setReceiveDate(LocalDate.now().minusDays(12));
        when(purchaseReceiveRecordRepository.findByPurchaseOrderId("PO-1"))
                .thenReturn(List.of(r));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID, Map.of(), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<Map<String, Object>> etaList = (List<Map<String, Object>>) data.get("eta");
        assertEquals(1, etaList.size());
        Map<String, Object> e = etaList.get(0);
        assertEquals(8, e.get("historicalAvgDays"));
        // MAX(8, 5) = 8
        assertEquals(8, e.get("recommendedEtaDays"));
    }

    @Test
    @DisplayName("UT-SDE-04: supplierId filter returns single supplier")
    @SuppressWarnings("unchecked")
    void supplierFilter() throws Exception {
        Supplier sup = new Supplier();
        sup.setId("SUP-X");
        sup.setFactoryId(FACTORY_ID);
        sup.setName("特定供应商");
        sup.setDeliveryDays(3);
        when(supplierRepository.findById("SUP-X")).thenReturn(Optional.of(sup));
        lenient().when(purchaseOrderRepository.findByFactoryIdAndSupplierId(FACTORY_ID, "SUP-X"))
                .thenReturn(List.of());

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("supplierId", "SUP-X"), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<Map<String, Object>> etaList = (List<Map<String, Object>>) data.get("eta");
        assertEquals(1, etaList.size());
        assertEquals("SUP-X", etaList.get(0).get("supplierId"));
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
