package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.service.inventory.PurchaseService;
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
import static org.mockito.Mockito.when;

/** Unit tests for {@link ReceiveWithLimitTool} (Sprint 8 P4). */
@ExtendWith(MockitoExtension.class)
class ReceiveWithLimitToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private ReceiveWithLimitTool tool;

    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;

    @Mock
    private PurchaseOrderItemRepository purchaseOrderItemRepository;

    @Mock
    private PurchaseService purchaseService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-RWL-01: metadata — WRITE + supportsPreview MEDIUM risk")
    void metadata() {
        assertEquals("receive_with_limit", tool.getToolName());
        assertEquals(ToolExecutor.ActionType.WRITE, tool.getActionType());
        assertEquals(ToolExecutor.RiskLevel.MEDIUM, tool.getRiskLevel());
        assertTrue(tool.supportsPreview());
    }

    @Test
    @DisplayName("UT-RWL-02: preview — within limit returns PREVIEW + canDo=true")
    void previewWithinLimit() throws Exception {
        PurchaseOrder po = po("po1", "PO-001");
        when(purchaseOrderRepository.findByIdAndFactoryId("po1", FACTORY_ID)).thenReturn(Optional.of(po));

        PurchaseOrderItem item = item(10L, "po1",
                new BigDecimal("100"), new BigDecimal("30")); // pending=70, cap=100
        when(purchaseOrderItemRepository.findByIdAndPurchaseOrderId(10L, "po1")).thenReturn(Optional.of(item));

        Map<String, Object> result = invokeDoPreview(
                Map.of("poId", "po1", "lineId", 10, "receivedQty", 50), ctx());
        assertEquals("PREVIEW", result.get("status"));
        assertEquals(true, result.get("canDo"));
        assertEquals(new BigDecimal("100"), result.get("ordered"));
        assertEquals(new BigDecimal("30"), result.get("alreadyReceived"));
        assertEquals(new BigDecimal("70"), result.get("pending"));
        assertEquals(new BigDecimal("100.00"), result.get("remainingCap"));
    }

    @Test
    @DisplayName("UT-RWL-03: preview — over 30% over-receive → OVER_LIMIT canDo=false")
    void previewOverLimit() throws Exception {
        PurchaseOrder po = po("po1", "PO-001");
        when(purchaseOrderRepository.findByIdAndFactoryId("po1", FACTORY_ID)).thenReturn(Optional.of(po));
        PurchaseOrderItem item = item(10L, "po1",
                new BigDecimal("100"), new BigDecimal("30")); // remainingCap=100, propose 110
        when(purchaseOrderItemRepository.findByIdAndPurchaseOrderId(10L, "po1")).thenReturn(Optional.of(item));

        Map<String, Object> result = invokeDoPreview(
                Map.of("poId", "po1", "lineId", 10, "receivedQty", 110), ctx());
        assertEquals("OVER_LIMIT", result.get("status"));
        assertEquals(false, result.get("canDo"));
        assertTrue(result.get("message").toString().contains("30%"));
    }

    @Test
    @DisplayName("UT-RWL-04: preview — fully received line → ALREADY_COMPLETED canDo=false")
    void previewAlreadyCompleted() throws Exception {
        PurchaseOrder po = po("po1", "PO-001");
        when(purchaseOrderRepository.findByIdAndFactoryId("po1", FACTORY_ID)).thenReturn(Optional.of(po));
        PurchaseOrderItem item = item(10L, "po1",
                new BigDecimal("100"), new BigDecimal("100"));
        when(purchaseOrderItemRepository.findByIdAndPurchaseOrderId(10L, "po1")).thenReturn(Optional.of(item));

        Map<String, Object> result = invokeDoPreview(
                Map.of("poId", "po1", "lineId", 10, "receivedQty", 10), ctx());
        assertEquals("ALREADY_COMPLETED", result.get("status"));
        assertEquals(false, result.get("canDo"));
    }

    @Test
    @DisplayName("UT-RWL-05: preview — PO not found returns PO_NOT_FOUND")
    void previewPoNotFound() throws Exception {
        when(purchaseOrderRepository.findByIdAndFactoryId("missing", FACTORY_ID)).thenReturn(Optional.empty());

        Map<String, Object> result = invokeDoPreview(
                Map.of("poId", "missing", "lineId", 1, "receivedQty", 5), ctx());
        assertEquals("PO_NOT_FOUND", result.get("status"));
        assertEquals(false, result.get("canDo"));
    }

    @Test
    @DisplayName("UT-RWL-06: execute over limit raises IllegalArgumentException")
    void executeOverLimitRejected() throws Exception {
        PurchaseOrder po = po("po1", "PO-001");
        when(purchaseOrderRepository.findByIdAndFactoryId("po1", FACTORY_ID)).thenReturn(Optional.of(po));
        PurchaseOrderItem item = item(10L, "po1",
                new BigDecimal("100"), new BigDecimal("30"));
        when(purchaseOrderItemRepository.findByIdAndPurchaseOrderId(10L, "po1")).thenReturn(Optional.of(item));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                invokeDoExecute(Map.of("poId", "po1", "lineId", 10, "receivedQty", 200), ctx()));
        assertTrue(ex.getMessage().contains("超") || ex.getMessage().contains("30"));
    }

    // ──────────────────────────────────────────────────────────────────────
    // Cross-tenant isolation tests (factoryId 多租户隔离)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("UT-RWL-ISO-01: preview — factory-A token cannot preview factory-B PO → PO_NOT_FOUND")
    void previewCrossTenantPoBlocked() throws Exception {
        // Factory B owns this PO; findByIdAndFactoryId returns empty for factory A
        when(purchaseOrderRepository.findByIdAndFactoryId("po-factory-b", FACTORY_ID))
                .thenReturn(Optional.empty());

        Map<String, Object> result = invokeDoPreview(
                Map.of("poId", "po-factory-b", "lineId", 1, "receivedQty", 10), ctx());
        assertEquals("PO_NOT_FOUND", result.get("status"));
        assertEquals(false, result.get("canDo"));
    }

    @Test
    @DisplayName("UT-RWL-ISO-02: execute — factory-A token cannot execute against factory-B PO → IllegalArgumentException")
    void executeCrossTenantPoBlocked() throws Exception {
        // DB rejects factory-A querying factory-B's PO
        when(purchaseOrderRepository.findByIdAndFactoryId("po-factory-b", FACTORY_ID))
                .thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                invokeDoExecute(Map.of("poId", "po-factory-b", "lineId", 1, "receivedQty", 10), ctx()));
        assertTrue(ex.getMessage().contains("不存在") || ex.getMessage().contains("非本工厂"));
    }

    @Test
    @DisplayName("UT-RWL-ISO-03: preview — cross-PO line access blocked (lineId belongs to different PO) → LINE_NOT_FOUND")
    void previewCrossPoLineBlocked() throws Exception {
        PurchaseOrder po = po("po1", "PO-001");
        when(purchaseOrderRepository.findByIdAndFactoryId("po1", FACTORY_ID)).thenReturn(Optional.of(po));
        // lineId=99 belongs to a different PO, not "po1"
        when(purchaseOrderItemRepository.findByIdAndPurchaseOrderId(99L, "po1"))
                .thenReturn(Optional.empty());

        Map<String, Object> result = invokeDoPreview(
                Map.of("poId", "po1", "lineId", 99, "receivedQty", 10), ctx());
        assertEquals("LINE_NOT_FOUND", result.get("status"));
        assertEquals(false, result.get("canDo"));
    }

    // ── helpers ──
    private PurchaseOrder po(String id, String number) {
        PurchaseOrder p = new PurchaseOrder();
        p.setId(id);
        p.setOrderNumber(number);
        p.setFactoryId(FACTORY_ID);
        p.setSupplierId("sup1");
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
    private Map<String, Object> invokeDoPreview(Map<String, Object> params,
            Map<String, Object> context) throws Exception {
        return (Map<String, Object>) invoke("doPreview", params, context);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeDoExecute(Map<String, Object> params,
            Map<String, Object> context) throws Exception {
        return (Map<String, Object>) invoke("doExecute", params, context);
    }

    private Object invoke(String name, Map<String, Object> params,
            Map<String, Object> context) throws Exception {
        var m = findMethod(tool.getClass(), name);
        m.setAccessible(true);
        try {
            return m.invoke(tool, FACTORY_ID, params, context);
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
