package com.cretas.aims.ai.tool.impl.production;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
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
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ProductionBatchCreateTool} (Sprint 10 Loop 5 灵魂 Tool).
 *
 * 防呆 4 大: R1 max, R2 context, R3 dropdown, R4 idempotent — 全覆盖.
 */
@ExtendWith(MockitoExtension.class)
class ProductionBatchCreateToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private ProductionBatchCreateTool tool;

    @Mock
    private ProductionBatchRepository productionBatchRepository;

    @Mock
    private SalesOrderRepository salesOrderRepository;

    @Mock
    private SalesOrderItemRepository salesOrderItemRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectObjectMapper(tool);
    }

    @Test
    @DisplayName("T1: getToolName = 'production_batch_create'")
    void toolName() {
        assertEquals("production_batch_create", tool.getToolName());
    }

    @Test
    @DisplayName("T2: ActionType = WRITE via *_create convention")
    void actionType() {
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.ActionType.WRITE, tool.getActionType());
    }

    @Test
    @DisplayName("T3: required params = productTypeId + quantity")
    void requiredParams() {
        var params = tool.getRequiredParameters();
        assertTrue(params.contains("productTypeId"));
        assertTrue(params.contains("quantity"));
    }

    @Test
    @DisplayName("T4: supportsPreview = true")
    void supportsPreview() {
        assertTrue(tool.supportsPreview());
    }

    // ===== R1 (max boundary) tests =====

    @Test
    @DisplayName("R1.1: preview returns maxAllowed = netShortage")
    @SuppressWarnings("unchecked")
    void previewReturnsMaxAllowed() throws Exception {
        seedDemand(new BigDecimal("100"), BigDecimal.ZERO);  // demand=100, no in-progress
        Map<String, Object> params = baseParams("PT-A", new BigDecimal("50"));

        Map<String, Object> result = invokeDoPreview(params);
        assertEquals("PREVIEW", result.get("status"));
        assertEquals(Boolean.TRUE, result.get("canDo"));
        assertEquals(0, ((BigDecimal) result.get("maxAllowed")).compareTo(new BigDecimal("100")));
        assertEquals(0, ((BigDecimal) result.get("netShortage")).compareTo(new BigDecimal("100")));
    }

    @Test
    @DisplayName("R1.2: execute() rejects quantity > maxAllowed")
    @SuppressWarnings("unchecked")
    void executeRejectsOverMax() throws Exception {
        seedDemand(new BigDecimal("50"), BigDecimal.ZERO);  // demand=50
        Map<String, Object> params = baseParams("PT-A", new BigDecimal("100"));

        Map<String, Object> result = invokeDoExecute(params);
        assertEquals("INVALID", result.get("status"));
        assertEquals(Boolean.FALSE, result.get("canDo"));
        assertEquals("QUANTITY_EXCEEDS_MAX", result.get("code"));
        String msg = result.get("message").toString();
        assertTrue(msg.contains("超过当前净缺量"), "msg=" + msg);
        verify(productionBatchRepository, never()).save(any());
    }

    @Test
    @DisplayName("R1.3: execute() rejects quantity = 0")
    void executeRejectsZeroQuantity() throws Exception {
        Map<String, Object> params = baseParams("PT-A", BigDecimal.ZERO);
        Map<String, Object> result = invokeDoExecute(params);
        assertEquals("INVALID", result.get("status"));
        verify(productionBatchRepository, never()).save(any());
    }

    // ===== R3 (dropdown enum) tests =====

    @Test
    @DisplayName("R3.1: execute() rejects invalid productionLine value")
    void executeRejectsInvalidLine() throws Exception {
        Map<String, Object> params = baseParams("PT-A", new BigDecimal("10"));
        params.put("productionLine", "INVALID_LINE_VALUE");
        Map<String, Object> result = invokeDoExecute(params);
        assertEquals("INVALID", result.get("status"));
        assertEquals("INVALID_PARAM", result.get("code"));
    }

    @Test
    @DisplayName("R3.2: execute() accepts DEDICATED_LINE_A / DEDICATED_LINE_B / SHARED_LINE / OTHER (case-insensitive)")
    @SuppressWarnings("unchecked")
    void executeAcceptsValidLines() throws Exception {
        // Inline seed (not seedDemand helper) — lenient to allow re-stubbing in loop
        SalesOrder so = new SalesOrder();
        so.setId("SO-SEED");
        so.setFactoryId(FACTORY_ID);
        so.setOrderNumber("SO-N");
        so.setStatus(SalesOrderStatus.CONFIRMED);
        SalesOrderItem item = new SalesOrderItem();
        item.setProductTypeId("PT-A");
        item.setProductName("卤猪蹄 200g");
        item.setQuantity(new BigDecimal("100"));
        item.setDeliveredQuantity(BigDecimal.ZERO);
        item.setUnit("kg");
        lenient().when(salesOrderRepository.findByFactoryIdAndStatusInOrderByCreatedAtDesc(
                eq(FACTORY_ID), any(Collection.class), any()))
                .thenReturn(List.of(so));
        lenient().when(salesOrderItemRepository.findBySalesOrderId("SO-SEED"))
                .thenReturn(List.of(item));
        lenient().when(productionBatchRepository.findByFactoryIdAndCreatedAtAfter(eq(FACTORY_ID), any()))
                .thenReturn(List.of());
        lenient().when(productionBatchRepository.existsByFactoryIdAndBatchNumber(anyString(), anyString()))
                .thenReturn(false);
        lenient().when(productionBatchRepository.save(any(ProductionBatch.class)))
                .thenAnswer(inv -> {
                    ProductionBatch b = inv.getArgument(0);
                    b.setId(123L);
                    return b;
                });

        for (String line : List.of("DEDICATED_LINE_A", "dedicated_line_b", "SHARED_LINE", "other")) {
            Map<String, Object> params = baseParams("PT-A", new BigDecimal("10"));
            params.put("productionLine", line);
            Map<String, Object> result = invokeDoExecute(params);
            assertEquals("CREATED", result.get("status"), "line=" + line);
        }
    }

    // ===== R4 (idempotent) tests =====

    @Test
    @DisplayName("R4.1: preview returns DUPLICATE when same productTypeId+date exists within 5min")
    @SuppressWarnings("unchecked")
    void previewReturnsDuplicate() throws Exception {
        seedDemandWithDup(new BigDecimal("100"), BigDecimal.ZERO);
        Map<String, Object> params = baseParams("PT-A", new BigDecimal("10"));
        params.put("scheduledDate", LocalDate.now().plusDays(1).toString());

        Map<String, Object> result = invokeDoPreview(params);
        assertEquals("DUPLICATE", result.get("status"));
        assertEquals(Boolean.FALSE, result.get("canDo"));
        assertEquals(999L, result.get("existingBatchId"));
        assertEquals("B-DUP-1", result.get("existingBatchNumber"));
        assertTrue(result.get("actionHint").toString().contains("/production/batches/999"));
    }

    @Test
    @DisplayName("R4.2: execute() returns DUPLICATE without saving when dup detected")
    void executeReturnsDuplicate() throws Exception {
        seedDemandWithDup(new BigDecimal("100"), BigDecimal.ZERO);
        Map<String, Object> params = baseParams("PT-A", new BigDecimal("10"));
        params.put("scheduledDate", LocalDate.now().plusDays(1).toString());

        Map<String, Object> result = invokeDoExecute(params);
        assertEquals("DUPLICATE", result.get("status"));
        assertEquals(0, result.get("count"));
        verify(productionBatchRepository, never()).save(any());
    }

    // ===== Happy path + ai_invocation_metadata =====

    @Test
    @DisplayName("Happy path: execute() saves PRODUCTION_BATCH with ai_invocation_metadata + R2 message")
    @SuppressWarnings("unchecked")
    void happyPath() throws Exception {
        seedDemand(new BigDecimal("100"), BigDecimal.ZERO);
        when(productionBatchRepository.existsByFactoryIdAndBatchNumber(anyString(), anyString()))
                .thenReturn(false);

        ArgumentCaptor<ProductionBatch> captor = ArgumentCaptor.forClass(ProductionBatch.class);
        when(productionBatchRepository.save(captor.capture())).thenAnswer(inv -> {
            ProductionBatch b = inv.getArgument(0);
            b.setId(456L);
            return b;
        });

        Map<String, Object> params = baseParams("PT-A", new BigDecimal("50"));
        params.put("productName", "卤猪蹄 200g");
        params.put("testRun", true);
        params.put("salesOrderId", "SO-X");

        Map<String, Object> result = invokeDoExecute(params);

        // Result envelope
        assertEquals("CREATED", result.get("status"));
        assertEquals(1, result.get("count"));
        assertEquals(456L, result.get("batchId"));
        assertNotNull(result.get("batchNumber"));
        // R2 context: message 必带 productName + batchNumber + quantity
        String msg = result.get("message").toString();
        assertTrue(msg.contains("50"), "msg should have qty 50, was: " + msg);
        assertTrue(msg.startsWith("已创批次"), "msg=" + msg);
        assertTrue(msg.contains("/production/batches/456"), "actionHint inline, msg=" + msg);

        // Persisted entity
        ProductionBatch saved = captor.getValue();
        assertEquals(FACTORY_ID, saved.getFactoryId());
        assertEquals("PT-A", saved.getProductTypeId());
        assertEquals(0, saved.getPlannedQuantity().compareTo(new BigDecimal("50")));
        assertEquals(0, saved.getQuantity().compareTo(new BigDecimal("50")));
        assertEquals(ProductionBatchStatus.PLANNED, saved.getStatus());
        assertEquals("DEDICATED_LINE_A", saved.getEquipmentName());

        // ai_invocation_metadata
        Map<String, Object> meta = saved.getAiInvocationMetadata();
        assertNotNull(meta, "aiInvocationMetadata must be set");
        assertEquals("sprint-10-loop-5", meta.get("source"));
        assertEquals(Boolean.TRUE, meta.get("testRun"));
        assertNotNull(meta.get("createdAt"));
        assertEquals("SO-X", meta.get("salesOrderId"));
    }

    @Test
    @DisplayName("Default productionLine = DEDICATED_LINE_A when not provided")
    @SuppressWarnings("unchecked")
    void defaultProductionLine() throws Exception {
        seedDemand(new BigDecimal("100"), BigDecimal.ZERO);
        Map<String, Object> params = baseParams("PT-A", new BigDecimal("10"));
        params.remove("productionLine");

        Map<String, Object> preview = invokeDoPreview(params);
        assertEquals("DEDICATED_LINE_A", preview.get("productionLine"));
    }

    @Test
    @DisplayName("Default scheduledDate = tomorrow when not provided")
    @SuppressWarnings("unchecked")
    void defaultScheduledDate() throws Exception {
        seedDemand(new BigDecimal("100"), BigDecimal.ZERO);
        Map<String, Object> params = baseParams("PT-A", new BigDecimal("10"));
        params.remove("scheduledDate");

        Map<String, Object> preview = invokeDoPreview(params);
        assertEquals(LocalDate.now().plusDays(1).toString(), preview.get("scheduledDate"));
    }

    // ===== helpers =====

    private void seedDemand(BigDecimal demand, BigDecimal inProgressRemaining) {
        SalesOrder so = new SalesOrder();
        so.setId("SO-SEED");
        so.setFactoryId(FACTORY_ID);
        so.setOrderNumber("SO-N");
        so.setStatus(SalesOrderStatus.CONFIRMED);

        SalesOrderItem item = new SalesOrderItem();
        item.setProductTypeId("PT-A");
        item.setProductName("卤猪蹄 200g");
        item.setQuantity(demand);
        item.setDeliveredQuantity(BigDecimal.ZERO);
        item.setUnit("kg");

        when(salesOrderRepository.findByFactoryIdAndStatusInOrderByCreatedAtDesc(
                eq(FACTORY_ID), any(Collection.class), any()))
                .thenReturn(List.of(so));
        when(salesOrderItemRepository.findBySalesOrderId("SO-SEED"))
                .thenReturn(List.of(item));

        // mock empty findByFactoryIdAndCreatedAtAfter (no dup, no in-progress unless specified)
        if (inProgressRemaining.compareTo(BigDecimal.ZERO) > 0) {
            ProductionBatch b = ProductionBatch.builder()
                    .id(1L).factoryId(FACTORY_ID).batchNumber("B-INPROG")
                    .productTypeId("PT-A")
                    .plannedQuantity(inProgressRemaining)
                    .actualQuantity(BigDecimal.ZERO)
                    .quantity(inProgressRemaining)
                    .unit("kg")
                    .status(ProductionBatchStatus.IN_PROGRESS)
                    .build();
            when(productionBatchRepository.findByFactoryIdAndCreatedAtAfter(eq(FACTORY_ID), any()))
                    .thenReturn(List.of(b));
        } else {
            when(productionBatchRepository.findByFactoryIdAndCreatedAtAfter(eq(FACTORY_ID), any()))
                    .thenReturn(List.of());
        }
    }

    /** Seed demand + 1 PLANNED batch in 5min window for same product+scheduledDate=tomorrow */
    private void seedDemandWithDup(BigDecimal demand, BigDecimal inProgressRemaining) {
        SalesOrder so = new SalesOrder();
        so.setId("SO-SEED");
        so.setFactoryId(FACTORY_ID);
        so.setOrderNumber("SO-N");
        so.setStatus(SalesOrderStatus.CONFIRMED);
        SalesOrderItem item = new SalesOrderItem();
        item.setProductTypeId("PT-A");
        item.setProductName("卤猪蹄 200g");
        item.setQuantity(demand);
        item.setDeliveredQuantity(BigDecimal.ZERO);
        item.setUnit("kg");
        when(salesOrderRepository.findByFactoryIdAndStatusInOrderByCreatedAtDesc(
                eq(FACTORY_ID), any(Collection.class), any()))
                .thenReturn(List.of(so));
        when(salesOrderItemRepository.findBySalesOrderId("SO-SEED"))
                .thenReturn(List.of(item));

        ProductionBatch dup = ProductionBatch.builder()
                .id(999L).factoryId(FACTORY_ID).batchNumber("B-DUP-1")
                .productTypeId("PT-A")
                .plannedQuantity(new BigDecimal("10"))
                .actualQuantity(BigDecimal.ZERO)
                .quantity(new BigDecimal("10"))
                .unit("kg")
                .status(ProductionBatchStatus.PLANNED)
                .startTime(LocalDate.now().plusDays(1).atStartOfDay())
                .build();
        when(productionBatchRepository.findByFactoryIdAndCreatedAtAfter(eq(FACTORY_ID), any()))
                .thenReturn(List.of(dup));
    }

    private Map<String, Object> baseParams(String productTypeId, BigDecimal qty) {
        Map<String, Object> p = new HashMap<>();
        p.put("productTypeId", productTypeId);
        p.put("quantity", qty);
        p.put("unit", "kg");
        p.put("scheduledDate", LocalDate.now().plusDays(1).toString());
        p.put("productionLine", "DEDICATED_LINE_A");
        return p;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeDoExecute(Map<String, Object> params) throws Exception {
        Map<String, Object> context = Map.of("factoryId", FACTORY_ID, "userId", 1L);
        var method = findMethod(tool.getClass(), "doExecute");
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(tool, FACTORY_ID, params, context);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeDoPreview(Map<String, Object> params) throws Exception {
        Map<String, Object> context = Map.of("factoryId", FACTORY_ID, "userId", 1L);
        var method = findMethod(tool.getClass(), "doPreview");
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(tool, FACTORY_ID, params, context);
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

    private void injectObjectMapper(Object tool) throws Exception {
        Field field = findField(tool.getClass(), "objectMapper");
        field.setAccessible(true);
        field.set(tool, objectMapper);
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
