package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.dto.inventory.CreatePurchaseRequisitionRequest;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.enums.PurchaseRequisitionStatus;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.inventory.PurchaseRequisition;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.service.inventory.PurchaseRequisitionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link RequisitionCreateTool} (Sprint 8 P4b 灵魂 Tool). */
@ExtendWith(MockitoExtension.class)
class RequisitionCreateToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private RequisitionCreateTool tool;

    @Mock
    private PurchaseRequisitionService requisitionService;

    @Mock
    private RawMaterialTypeRepository rawMaterialTypeRepository;

    @Mock
    private SupplierRepository supplierRepository;

    @Mock
    private PurchaseOrderItemRepository purchaseOrderItemRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-REQ-01: metadata + WRITE + Preview")
    void metadata() {
        assertEquals("requisition_create", tool.getToolName());
        assertTrue(tool.supportsPreview());
        assertEquals(ToolExecutor.ActionType.WRITE, tool.getActionType());
        assertEquals(ToolExecutor.RiskLevel.MEDIUM, tool.getRiskLevel());
    }

    @Test
    @DisplayName("UT-REQ-02: preview — happy path with budget computed from historical avg")
    @SuppressWarnings("unchecked")
    void previewHappyPath() throws Exception {
        RawMaterialType mat = new RawMaterialType();
        mat.setId("M001");
        mat.setFactoryId(FACTORY_ID);
        mat.setName("猪肉");
        mat.setCode("PIG");
        mat.setUnit("kg");
        when(rawMaterialTypeRepository.findById("M001")).thenReturn(Optional.of(mat));

        Supplier sup = new Supplier();
        sup.setId("SUP-1");
        sup.setFactoryId(FACTORY_ID);
        sup.setName("肉联厂");
        sup.setDeliveryDays(3);
        when(supplierRepository.findById("SUP-1")).thenReturn(Optional.of(sup));

        // Historical price: avg = (10*20 + 20*30) / 30 = 800/30 = 26.6667
        PurchaseOrderItem h1 = new PurchaseOrderItem();
        h1.setQuantity(new BigDecimal("10"));
        h1.setUnitPrice(new BigDecimal("20"));
        PurchaseOrderItem h2 = new PurchaseOrderItem();
        h2.setQuantity(new BigDecimal("20"));
        h2.setUnitPrice(new BigDecimal("30"));
        when(purchaseOrderItemRepository.findByMaterialTypeId("M001"))
                .thenReturn(List.of(h1, h2));

        Map<String, Object> params = new HashMap<>();
        params.put("materialId", "M001");
        params.put("quantity", new BigDecimal("100"));
        params.put("expectedDeliveryDate", LocalDate.now().plusDays(7).toString());
        params.put("supplierId", "SUP-1");
        params.put("notes", "test");

        Map<String, Object> result = invoke("doPreview", FACTORY_ID, params, ctx());
        assertEquals("PREVIEW", result.get("status"));
        assertEquals(true, result.get("canDo"));
        assertEquals("猪肉", result.get("materialName"));
        assertEquals("肉联厂", result.get("supplierName"));
        BigDecimal unitPrice = (BigDecimal) result.get("estimatedUnitPrice");
        BigDecimal budget = (BigDecimal) result.get("estimatedBudget");
        // 26.6667 * 100 = 2666.67
        assertEquals(0, budget.compareTo(new BigDecimal("2666.67")),
                "Expected 2666.67, got " + budget);
        assertNotNull(unitPrice);
        assertNotNull(result.get("approvalChainHint"));
    }

    @Test
    @DisplayName("UT-REQ-03: preview — past date returns canDo=false")
    @SuppressWarnings("unchecked")
    void previewPastDate() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("materialId", "M001");
        params.put("quantity", new BigDecimal("100"));
        params.put("expectedDeliveryDate", LocalDate.now().minusDays(1).toString());

        Map<String, Object> result = invoke("doPreview", FACTORY_ID, params, ctx());
        assertEquals("PAST_DATE", result.get("status"));
        assertEquals(false, result.get("canDo"));
    }

    @Test
    @DisplayName("UT-REQ-04: execute — creates DRAFT requisition via service")
    @SuppressWarnings("unchecked")
    void executeCreatesDraft() throws Exception {
        RawMaterialType mat = new RawMaterialType();
        mat.setId("M001");
        mat.setFactoryId(FACTORY_ID);
        mat.setName("猪肉");
        mat.setUnit("kg");
        when(rawMaterialTypeRepository.findById("M001")).thenReturn(Optional.of(mat));

        PurchaseRequisition pr = new PurchaseRequisition();
        pr.setId("PR-1");
        pr.setRequisitionNumber("PR-20260520-001");
        pr.setStatus(PurchaseRequisitionStatus.DRAFT);
        when(requisitionService.createRequisition(anyString(), anyLong(), any(CreatePurchaseRequisitionRequest.class)))
                .thenReturn(pr);

        Map<String, Object> params = new HashMap<>();
        params.put("materialId", "M001");
        params.put("quantity", new BigDecimal("100"));
        params.put("expectedDeliveryDate", LocalDate.now().plusDays(5).toString());
        params.put("notes", "低库存");

        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals("PR-1", data.get("requisitionId"));
        assertEquals("PR-20260520-001", data.get("requisitionNumber"));
        assertEquals("DRAFT", data.get("status"));

        // Verify service was called with correct request
        ArgumentCaptor<CreatePurchaseRequisitionRequest> captor =
                ArgumentCaptor.forClass(CreatePurchaseRequisitionRequest.class);
        verify(requisitionService).createRequisition(anyString(), anyLong(), captor.capture());
        CreatePurchaseRequisitionRequest req = captor.getValue();
        assertEquals(1, req.getRequestedItems().size());
        Map<String, Object> line = req.getRequestedItems().get(0);
        assertEquals("M001", line.get("materialTypeId"));
        assertEquals("猪肉", line.get("materialName"));
        assertEquals(0, ((BigDecimal) line.get("quantity")).compareTo(new BigDecimal("100")));
    }

    @Test
    @DisplayName("UT-REQ-05: execute — invalid quantity throws")
    void executeInvalidQty() throws Exception {
        // Need mocks lenient enough for the validation path
        lenient().when(rawMaterialTypeRepository.findById(anyString())).thenReturn(Optional.empty());

        Map<String, Object> params = new HashMap<>();
        params.put("materialId", "M001");
        params.put("quantity", new BigDecimal("-5"));
        params.put("expectedDeliveryDate", LocalDate.now().plusDays(5).toString());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> invoke("doExecute", FACTORY_ID, params, ctx()));
        assertTrue(ex.getMessage().contains("数量") || ex.getMessage().contains("> 0"));
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
