package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReceiveConfirmCreateTool} (Sprint 10 Loop 2).
 *
 * <p>Focus: factoryId 租户隔离、R1 max boundary、R4 idempotent dedup.
 */
@ExtendWith(MockitoExtension.class)
class ReceiveConfirmCreateToolTest {

    private static final String FACTORY_A = "F006";
    private static final String FACTORY_B = "F999";

    @InjectMocks
    private ReceiveConfirmCreateTool tool;

    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;

    @Mock
    private PurchaseOrderItemRepository purchaseOrderItemRepository;

    @Mock
    private PurchaseReceiveRecordRepository receiveRecordRepository;

    @Mock
    private PurchaseService purchaseService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    // ── metadata ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("UT-RCC-01: metadata — WRITE + supportsPreview + MEDIUM risk")
    void metadata() {
        assertEquals("receive_confirm_create", tool.getToolName());
        assertEquals(ToolExecutor.ActionType.WRITE, tool.getActionType());
        assertEquals(ToolExecutor.RiskLevel.MEDIUM, tool.getRiskLevel());
        assertTrue(tool.supportsPreview());
    }

    // ── happy path ────────────────────────────────────────────────────────

    @Test
    @DisplayName("UT-RCC-02: preview — within limit → PREVIEW + canDo=true")
    void previewWithinLimit() throws Exception {
        PurchaseOrder po = po("po1", "PO-001", FACTORY_A);
        when(purchaseOrderRepository.findByIdAndFactoryId("po1", FACTORY_A))
                .thenReturn(Optional.of(po));
        PurchaseOrderItem item = item(10L, "po1", new BigDecimal("100"), new BigDecimal("20"));
        when(purchaseOrderItemRepository.findByIdAndPurchaseOrderId(10L, "po1"))
                .thenReturn(Optional.of(item));
        when(receiveRecordRepository.findByFactoryIdAndPurchaseOrderIdOrderByCreatedAtAsc(FACTORY_A, "po1"))
                .thenReturn(Collections.emptyList());

        Map<String, Object> result = invokeDoPreview(
                Map.of("poId", "po1", "lineId", 10, "receivedQty", 50), ctxA());
        assertEquals("PREVIEW", result.get("status"));
        assertEquals(true, result.get("canDo"));
    }

    @Test
    @DisplayName("UT-RCC-03: preview — over 30% over-receive → OVER_LIMIT canDo=false")
    void previewOverLimit() throws Exception {
        PurchaseOrder po = po("po1", "PO-001", FACTORY_A);
        when(purchaseOrderRepository.findByIdAndFactoryId("po1", FACTORY_A))
                .thenReturn(Optional.of(po));
        PurchaseOrderItem item = item(10L, "po1", new BigDecimal("100"), new BigDecimal("20"));
        when(purchaseOrderItemRepository.findByIdAndPurchaseOrderId(10L, "po1"))
                .thenReturn(Optional.of(item));

        Map<String, Object> result = invokeDoPreview(
                Map.of("poId", "po1", "lineId", 10, "receivedQty", 120), ctxA());
        assertEquals("OVER_LIMIT", result.get("status"));
        assertEquals(false, result.get("canDo"));
        assertTrue(result.get("message").toString().contains("30%"));
    }

    @Test
    @DisplayName("UT-RCC-04: preview — invalid receiveStatus → IllegalArgumentException (before PO lookup)")
    void previewInvalidStatus() throws Exception {
        // validateReceiveStatus is called before PO lookup, so no PO stubbing needed
        assertThrows(IllegalArgumentException.class, () ->
                invokeDoPreview(Map.of("poId", "po1", "lineId", 10, "receivedQty", 10,
                        "receiveStatus", "INVALID_STATUS"), ctxA()));
    }

    // ── factoryId 多租户隔离 (cross-tenant isolation) ──────────────────────

    @Test
    @DisplayName("UT-RCC-ISO-01: preview — factory-A token cannot access factory-B PO → PO_NOT_FOUND")
    void previewCrossTenantPoBlocked() throws Exception {
        // Factory B owns "po-b"; findByIdAndFactoryId returns empty for factory A
        when(purchaseOrderRepository.findByIdAndFactoryId("po-b", FACTORY_A))
                .thenReturn(Optional.empty());

        Map<String, Object> result = invokeDoPreview(
                Map.of("poId", "po-b", "lineId", 1, "receivedQty", 10), ctxA());
        assertEquals("PO_NOT_FOUND", result.get("status"));
        assertEquals(false, result.get("canDo"));
    }

    @Test
    @DisplayName("UT-RCC-ISO-02: execute — factory-A token cannot execute factory-B PO → IllegalArgumentException")
    void executeCrossTenantPoBlocked() throws Exception {
        when(purchaseOrderRepository.findByIdAndFactoryId("po-b", FACTORY_A))
                .thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                invokeDoExecute(Map.of("poId", "po-b", "lineId", 1, "receivedQty", 10), ctxA()));
        assertTrue(ex.getMessage().contains("不存在") || ex.getMessage().contains("非本工厂"));
    }

    @Test
    @DisplayName("UT-RCC-ISO-03: preview — cross-PO line blocked (lineId belongs to different PO)")
    void previewCrossPoLineBlocked() throws Exception {
        PurchaseOrder po = po("po1", "PO-001", FACTORY_A);
        when(purchaseOrderRepository.findByIdAndFactoryId("po1", FACTORY_A))
                .thenReturn(Optional.of(po));
        // lineId=99 belongs to po2, not po1
        when(purchaseOrderItemRepository.findByIdAndPurchaseOrderId(99L, "po1"))
                .thenReturn(Optional.empty());

        Map<String, Object> result = invokeDoPreview(
                Map.of("poId", "po1", "lineId", 99, "receivedQty", 10), ctxA());
        assertEquals("LINE_NOT_FOUND", result.get("status"));
        assertEquals(false, result.get("canDo"));
    }

    @Test
    @DisplayName("UT-RCC-ISO-04: execute — cross-PO line blocked → IllegalArgumentException")
    void executeCrossPoLineBlocked() throws Exception {
        PurchaseOrder po = po("po1", "PO-001", FACTORY_A);
        when(purchaseOrderRepository.findByIdAndFactoryId("po1", FACTORY_A))
                .thenReturn(Optional.of(po));
        when(purchaseOrderItemRepository.findByIdAndPurchaseOrderId(99L, "po1"))
                .thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                invokeDoExecute(Map.of("poId", "po1", "lineId", 99, "receivedQty", 10), ctxA()));
        assertTrue(ex.getMessage().contains("不存在") || ex.getMessage().contains("行项目"));
    }

    @Test
    @DisplayName("UT-RCC-ISO-05: checkDuplicate uses factory-scoped query — cross-factory records not visible")
    void dedupUsesFactoryScopedQuery() throws Exception {
        // dedup query only returns factory A records; factory B's records are invisible
        when(receiveRecordRepository.findByFactoryIdAndPurchaseOrderIdOrderByCreatedAtAsc(FACTORY_A, "po1"))
                .thenReturn(Collections.emptyList());

        PurchaseOrder po = po("po1", "PO-001", FACTORY_A);
        when(purchaseOrderRepository.findByIdAndFactoryId("po1", FACTORY_A))
                .thenReturn(Optional.of(po));
        PurchaseOrderItem item = item(10L, "po1", new BigDecimal("100"), new BigDecimal("0"));
        when(purchaseOrderItemRepository.findByIdAndPurchaseOrderId(10L, "po1"))
                .thenReturn(Optional.of(item));

        Map<String, Object> result = invokeDoPreview(
                Map.of("poId", "po1", "lineId", 10, "receivedQty", 50), ctxA());
        // No cross-factory dedup interference — should proceed normally
        assertNotEquals("DUPLICATE", result.get("status"));
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private PurchaseOrder po(String id, String number, String factoryId) {
        PurchaseOrder p = new PurchaseOrder();
        p.setId(id);
        p.setOrderNumber(number);
        p.setFactoryId(factoryId);
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

    private Map<String, Object> ctxA() {
        Map<String, Object> c = new HashMap<>();
        c.put("factoryId", FACTORY_A);
        c.put("userId", 1L);
        return c;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeDoPreview(Map<String, Object> params,
            Map<String, Object> context) throws Exception {
        return (Map<String, Object>) invoke("doPreview", FACTORY_A, params, context);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeDoExecute(Map<String, Object> params,
            Map<String, Object> context) throws Exception {
        return (Map<String, Object>) invoke("doExecute", FACTORY_A, params, context);
    }

    private Object invoke(String name, String factoryId, Map<String, Object> params,
            Map<String, Object> context) throws Exception {
        var m = findMethod(tool.getClass(), name);
        m.setAccessible(true);
        try {
            return m.invoke(tool, factoryId, params, context);
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
