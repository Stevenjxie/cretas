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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductionDemandQueryToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private ProductionDemandQueryTool tool;

    @Mock
    private SalesOrderRepository salesOrderRepository;

    @Mock
    private SalesOrderItemRepository salesOrderItemRepository;

    @Mock
    private ProductionBatchRepository productionBatchRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectObjectMapper(tool);
    }

    @Test
    @DisplayName("T1: getToolName = 'production_demand_query'")
    void toolName() {
        assertEquals("production_demand_query", tool.getToolName());
    }

    @Test
    @DisplayName("T2: ActionType = READ")
    void actionType() {
        assertEquals(com.cretas.aims.ai.tool.ToolExecutor.ActionType.READ, tool.getActionType());
    }

    @Test
    @DisplayName("T3: No active SOs → empty result with friendly message")
    @SuppressWarnings("unchecked")
    void noActiveSOs() throws Exception {
        when(salesOrderRepository.findByFactoryIdAndStatusInOrderByCreatedAtDesc(
                eq(FACTORY_ID), any(Collection.class), any()))
                .thenReturn(List.of());

        Map<String, Object> result = invokeDoExecute(FACTORY_ID, new HashMap<>());
        String msg = result.get("message").toString();
        assertTrue(msg.contains("无未完成销售订单"),
                "should say no active SOs, was: " + msg);
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(0, data.get("totalCount"));
    }

    @Test
    @DisplayName("T4: SO with pending qty 10, no in-progress batch → 1 product, netShortage=10")
    @SuppressWarnings("unchecked")
    void simpleShortageNoInProgress() throws Exception {
        SalesOrder so = buildSO("SO1", SalesOrderStatus.FINANCE_APPROVED);
        SalesOrderItem item = buildItem("PT-A", "卤猪蹄 200g", new BigDecimal("100"), new BigDecimal("90"));
        when(salesOrderRepository.findByFactoryIdAndStatusInOrderByCreatedAtDesc(
                eq(FACTORY_ID), any(Collection.class), any()))
                .thenReturn(List.of(so));
        when(salesOrderItemRepository.findBySalesOrderId("SO1"))
                .thenReturn(List.of(item));
        when(productionBatchRepository.findByFactoryIdAndCreatedAtAfter(eq(FACTORY_ID), any()))
                .thenReturn(List.of());

        Map<String, Object> result = invokeDoExecute(FACTORY_ID, new HashMap<>());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(1, data.get("totalCount"));
        List<Map<String, Object>> products = (List<Map<String, Object>>) data.get("products");
        assertEquals(1, products.size());
        Map<String, Object> p = products.get(0);
        assertEquals("PT-A", p.get("productTypeId"));
        assertEquals("卤猪蹄 200g", p.get("productName"));
        assertEquals(0, ((BigDecimal) p.get("totalDemand")).compareTo(new BigDecimal("10")));
        assertEquals(0, ((BigDecimal) p.get("netShortage")).compareTo(new BigDecimal("10")));
        assertEquals(0, ((BigDecimal) p.get("inProgressQuantity")).compareTo(BigDecimal.ZERO));
        assertEquals("DEDICATED_LINE_A", p.get("recommendedLine"));
    }

    @Test
    @DisplayName("T5: SO pending 100 + in-progress batch 30 (planned) - 10 (actual) = 20 remaining → netShortage=80")
    @SuppressWarnings("unchecked")
    void inProgressDeducted() throws Exception {
        SalesOrder so = buildSO("SO2", SalesOrderStatus.CONFIRMED);
        SalesOrderItem item = buildItem("PT-B", "卤鸡爪", new BigDecimal("100"), BigDecimal.ZERO);
        when(salesOrderRepository.findByFactoryIdAndStatusInOrderByCreatedAtDesc(
                eq(FACTORY_ID), any(Collection.class), any()))
                .thenReturn(List.of(so));
        when(salesOrderItemRepository.findBySalesOrderId("SO2"))
                .thenReturn(List.of(item));

        ProductionBatch active = ProductionBatch.builder()
                .id(1L)
                .factoryId(FACTORY_ID)
                .batchNumber("B-001")
                .productTypeId("PT-B")
                .productName("卤鸡爪")
                .plannedQuantity(new BigDecimal("30"))
                .actualQuantity(new BigDecimal("10"))
                .quantity(new BigDecimal("30"))
                .unit("kg")
                .status(ProductionBatchStatus.IN_PROGRESS)
                .build();
        when(productionBatchRepository.findByFactoryIdAndCreatedAtAfter(eq(FACTORY_ID), any()))
                .thenReturn(List.of(active));

        Map<String, Object> result = invokeDoExecute(FACTORY_ID, new HashMap<>());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<Map<String, Object>> products = (List<Map<String, Object>>) data.get("products");
        Map<String, Object> p = products.get(0);
        assertEquals(0, ((BigDecimal) p.get("inProgressQuantity")).compareTo(new BigDecimal("20")));
        assertEquals(0, ((BigDecimal) p.get("netShortage")).compareTo(new BigDecimal("80")));
    }

    @Test
    @DisplayName("T6: in-progress totally covers demand → product filtered out")
    @SuppressWarnings("unchecked")
    void inProgressCoversAll() throws Exception {
        SalesOrder so = buildSO("SO3", SalesOrderStatus.CONFIRMED);
        SalesOrderItem item = buildItem("PT-C", "X", new BigDecimal("50"), BigDecimal.ZERO);
        when(salesOrderRepository.findByFactoryIdAndStatusInOrderByCreatedAtDesc(
                eq(FACTORY_ID), any(Collection.class), any()))
                .thenReturn(List.of(so));
        when(salesOrderItemRepository.findBySalesOrderId("SO3"))
                .thenReturn(List.of(item));

        ProductionBatch active = ProductionBatch.builder()
                .id(1L).factoryId(FACTORY_ID).batchNumber("B-002")
                .productTypeId("PT-C").productName("X")
                .plannedQuantity(new BigDecimal("100"))
                .actualQuantity(BigDecimal.ZERO)
                .quantity(new BigDecimal("100"))
                .unit("kg")
                .status(ProductionBatchStatus.PLANNED)
                .build();
        when(productionBatchRepository.findByFactoryIdAndCreatedAtAfter(eq(FACTORY_ID), any()))
                .thenReturn(List.of(active));

        Map<String, Object> result = invokeDoExecute(FACTORY_ID, new HashMap<>());
        String msg = result.get("message").toString();
        assertTrue(msg.contains("无需新排产"), "msg=" + msg);
    }

    @Test
    @DisplayName("T7: Multiple SO lines for same product aggregate. R2: orderRefs carries up to 5.")
    @SuppressWarnings("unchecked")
    void multipleLinesAggregate() throws Exception {
        SalesOrder so1 = buildSO("SO-1", SalesOrderStatus.CONFIRMED);
        SalesOrder so2 = buildSO("SO-2", SalesOrderStatus.PARTIAL_DELIVERED);
        SalesOrderItem item1 = buildItem("PT-D", "甲鱼", new BigDecimal("20"), BigDecimal.ZERO);
        SalesOrderItem item2 = buildItem("PT-D", "甲鱼", new BigDecimal("30"), BigDecimal.ZERO);
        when(salesOrderRepository.findByFactoryIdAndStatusInOrderByCreatedAtDesc(
                eq(FACTORY_ID), any(Collection.class), any()))
                .thenReturn(List.of(so1, so2));
        when(salesOrderItemRepository.findBySalesOrderId("SO-1")).thenReturn(List.of(item1));
        when(salesOrderItemRepository.findBySalesOrderId("SO-2")).thenReturn(List.of(item2));
        when(productionBatchRepository.findByFactoryIdAndCreatedAtAfter(eq(FACTORY_ID), any()))
                .thenReturn(List.of());

        Map<String, Object> result = invokeDoExecute(FACTORY_ID, new HashMap<>());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<Map<String, Object>> products = (List<Map<String, Object>>) data.get("products");
        Map<String, Object> p = products.get(0);
        assertEquals(0, ((BigDecimal) p.get("totalDemand")).compareTo(new BigDecimal("50")));
        assertEquals(2, p.get("orderCount"));
        List<Map<String, Object>> orderRefs = (List<Map<String, Object>>) p.get("orderRefs");
        assertEquals(2, orderRefs.size());
    }

    // ===== helpers =====

    private SalesOrder buildSO(String id, SalesOrderStatus status) {
        SalesOrder so = new SalesOrder();
        so.setId(id);
        so.setFactoryId(FACTORY_ID);
        so.setOrderNumber("SO-N-" + id);
        so.setStatus(status);
        return so;
    }

    private SalesOrderItem buildItem(String productTypeId, String productName,
                                       BigDecimal quantity, BigDecimal delivered) {
        SalesOrderItem item = new SalesOrderItem();
        item.setProductTypeId(productTypeId);
        item.setProductName(productName);
        item.setQuantity(quantity);
        item.setDeliveredQuantity(delivered);
        item.setUnit("kg");
        return item;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeDoExecute(String factoryId, Map<String, Object> params) throws Exception {
        Map<String, Object> context = Map.of("factoryId", factoryId, "userId", 1L);
        var method = findMethod(tool.getClass(), "doExecute");
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(tool, factoryId, params, context);
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
