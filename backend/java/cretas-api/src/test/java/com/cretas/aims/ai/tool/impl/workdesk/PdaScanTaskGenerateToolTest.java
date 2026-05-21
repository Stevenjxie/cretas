package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.entity.enums.PurchaseOrderStatus;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/** Unit tests for {@link PdaScanTaskGenerateTool} (Sprint 8 P4). */
@ExtendWith(MockitoExtension.class)
class PdaScanTaskGenerateToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private PdaScanTaskGenerateTool tool;

    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;

    @Mock
    private PurchaseOrderItemRepository purchaseOrderItemRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-PST-01: metadata — GENERATE convention, poIds required")
    void metadata() {
        assertEquals("pda_scan_task_generate", tool.getToolName());
        assertEquals(List.of("poIds"), tool.getRequiredParameters());
    }

    @Test
    @DisplayName("UT-PST-02: valid PO with pending items → task generated with items")
    @SuppressWarnings("unchecked")
    void validPosGenerateTaskWithItems() throws Exception {
        PurchaseOrder po = po("po1", "PO-001", PurchaseOrderStatus.APPROVED);
        when(purchaseOrderRepository.findById("po1")).thenReturn(Optional.of(po));
        PurchaseOrderItem item = item(10L, "po1",
                new BigDecimal("100"), new BigDecimal("30")); // pending=70
        when(purchaseOrderItemRepository.findByPurchaseOrderId("po1")).thenReturn(List.of(item));

        Map<String, Object> result = invokeDoExecute(Map.of("poIds", List.of("po1")), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        Map<String, Object> task = (Map<String, Object>) data.get("task");
        assertNotNull(task.get("token"));
        assertEquals(1, task.get("totalItems"));
        List<Map<String, Object>> items = (List<Map<String, Object>>) task.get("items");
        assertEquals(new BigDecimal("70"), items.get(0).get("expectedQty"));
        assertEquals(false, items.get(0).get("scanned"));
        assertTrue(task.get("qrPayload").toString().startsWith("cretas://pda-scan?token="));
    }

    @Test
    @DisplayName("UT-PST-03: COMPLETED PO marked invalid, not included in items")
    @SuppressWarnings("unchecked")
    void completedPoMarkedInvalid() throws Exception {
        PurchaseOrder done = po("po1", "PO-001", PurchaseOrderStatus.COMPLETED);
        when(purchaseOrderRepository.findById("po1")).thenReturn(Optional.of(done));

        Map<String, Object> result = invokeDoExecute(Map.of("poIds", List.of("po1")), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        Map<String, Object> task = (Map<String, Object>) data.get("task");
        List<String> invalid = (List<String>) task.get("invalidPOs");
        assertTrue(invalid.contains("PO-001"));
        assertEquals(0, task.get("totalItems"));
    }

    @Test
    @DisplayName("UT-PST-04: empty poIds → IllegalArgumentException")
    void emptyPoIdsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> invokeDoExecute(Map.of("poIds", List.of()), ctx()));
        assertTrue(ex.getMessage().contains("poIds"));
    }

    // ── helpers ──
    private PurchaseOrder po(String id, String number, PurchaseOrderStatus status) {
        PurchaseOrder p = new PurchaseOrder();
        p.setId(id);
        p.setOrderNumber(number);
        p.setFactoryId(FACTORY_ID);
        p.setStatus(status);
        return p;
    }

    private PurchaseOrderItem item(Long id, String poId, BigDecimal qty, BigDecimal received) {
        PurchaseOrderItem i = new PurchaseOrderItem();
        i.setId(id);
        i.setPurchaseOrderId(poId);
        i.setMaterialTypeId("m1");
        i.setMaterialName("卤猪蹄毛料");
        i.setQuantity(qty);
        i.setReceivedQuantity(received);
        i.setUnit("kg");
        return i;
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
