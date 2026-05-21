package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.ToolExecutor;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

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

/** Unit tests for {@link MaterialTodayReceivingQueryTool} (Sprint 8 P4). */
@ExtendWith(MockitoExtension.class)
class MaterialTodayReceivingQueryToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private MaterialTodayReceivingQueryTool tool;

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
    @DisplayName("UT-MTRQ-01: metadata — READ tool, default daysAhead=1")
    void metadata() {
        assertEquals("material_today_receiving_query", tool.getToolName());
        assertTrue(tool.getDescription().contains("今天要收什么货"));
        assertEquals(ToolExecutor.ActionType.READ, tool.getActionType());
        assertTrue(tool.getRequiredParameters().isEmpty());
    }

    @Test
    @DisplayName("UT-MTRQ-02: PO with pending line in today window returns row with R1 max")
    @SuppressWarnings("unchecked")
    void inWindowRowsHaveR1Max() throws Exception {
        PurchaseOrder po = po("po1", "PO-001", LocalDate.now(), PurchaseOrderStatus.APPROVED);
        when(purchaseOrderRepository.findByFactoryIdOrderByCreatedAtDesc(
                anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(po)));

        PurchaseOrderItem item = item(10L, "po1", "卤猪蹄毛料",
                new BigDecimal("100"), new BigDecimal("30"));
        when(purchaseOrderItemRepository.findByPurchaseOrderId("po1"))
                .thenReturn(List.of(item));

        Map<String, Object> result = invokeDoExecute(Map.of("daysAhead", 1), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        assertEquals(1, rows.size());
        Map<String, Object> r0 = rows.get(0);
        // R1 max: pending=70, overReceiveLimit=100*0.30=30, remainingCap=100
        assertEquals(new BigDecimal("70"), r0.get("pendingQuantity"));
        assertEquals(new BigDecimal("100.00"), r0.get("remainingCap"));
        assertTrue(r0.get("displayHint").toString().contains("还要收 70"));
    }

    @Test
    @DisplayName("UT-MTRQ-03: status DRAFT/COMPLETED excluded (not receivable)")
    @SuppressWarnings("unchecked")
    void nonReceivableStatusExcluded() throws Exception {
        PurchaseOrder draft = po("po1", "PO-DRAFT", LocalDate.now(), PurchaseOrderStatus.DRAFT);
        PurchaseOrder done = po("po2", "PO-DONE", LocalDate.now(), PurchaseOrderStatus.COMPLETED);
        when(purchaseOrderRepository.findByFactoryIdOrderByCreatedAtDesc(
                anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(draft, done)));

        Map<String, Object> result = invokeDoExecute(Map.of(), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(0, data.get("totalLines"));
        assertEquals(0, data.get("totalPOs"));
    }

    @Test
    @DisplayName("UT-MTRQ-04: fully received line (pending=0) excluded")
    @SuppressWarnings("unchecked")
    void fullyReceivedLineExcluded() throws Exception {
        PurchaseOrder po = po("po1", "PO-001", LocalDate.now(), PurchaseOrderStatus.APPROVED);
        when(purchaseOrderRepository.findByFactoryIdOrderByCreatedAtDesc(
                anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(po)));
        PurchaseOrderItem full = item(11L, "po1", "M2",
                new BigDecimal("50"), new BigDecimal("50"));
        when(purchaseOrderItemRepository.findByPurchaseOrderId("po1"))
                .thenReturn(List.of(full));

        Map<String, Object> result = invokeDoExecute(Map.of(), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(0, data.get("totalLines"));
    }

    // ── helpers ──
    private PurchaseOrder po(String id, String number, LocalDate expected,
            PurchaseOrderStatus status) {
        PurchaseOrder p = new PurchaseOrder();
        p.setId(id);
        p.setOrderNumber(number);
        p.setFactoryId(FACTORY_ID);
        p.setExpectedDeliveryDate(expected);
        p.setStatus(status);
        return p;
    }

    private PurchaseOrderItem item(Long id, String poId, String name,
            BigDecimal qty, BigDecimal received) {
        PurchaseOrderItem i = new PurchaseOrderItem();
        i.setId(id);
        i.setPurchaseOrderId(poId);
        i.setMaterialName(name);
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

    private java.lang.reflect.Method findMethod(Class<?> c, String name) {
        while (c != null) {
            for (var m : c.getDeclaredMethods()) if (m.getName().equals(name)) return m;
            c = c.getSuperclass();
        }
        throw new IllegalArgumentException("not found: " + name);
    }

    private void injectField(Object t, String name, Object v) throws Exception {
        Field f = findField(t.getClass(), name);
        f.setAccessible(true);
        f.set(t, v);
    }

    private Field findField(Class<?> c, String name) {
        while (c != null) {
            try { return c.getDeclaredField(name); }
            catch (NoSuchFieldException e) { c = c.getSuperclass(); }
        }
        throw new IllegalArgumentException("field not found: " + name);
    }
}
