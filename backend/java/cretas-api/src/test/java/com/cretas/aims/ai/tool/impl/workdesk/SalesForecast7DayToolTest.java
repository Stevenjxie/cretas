package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
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
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Unit tests for {@link SalesForecast7DayTool} (Sprint 8 P4b). */
@ExtendWith(MockitoExtension.class)
class SalesForecast7DayToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private SalesForecast7DayTool tool;

    @Mock
    private SalesOrderRepository salesOrderRepository;

    @Mock
    private SalesOrderItemRepository salesOrderItemRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-SF7-01: metadata")
    void metadata() {
        assertEquals("sales_forecast_7day", tool.getToolName());
        assertTrue(tool.getDescription().contains("7 天"));
    }

    @Test
    @DisplayName("UT-SF7-02: empty SO window returns no forecasts")
    @SuppressWarnings("unchecked")
    void emptyWindow() throws Exception {
        when(salesOrderRepository.findByFactoryIdAndDateRange(anyString(), any(), any()))
                .thenReturn(List.of());
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, Map.of(), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<?> forecasts = (List<?>) data.get("forecasts");
        assertTrue(forecasts.isEmpty());
        assertTrue(((String) result.get("message")).contains("无销售订单"));
    }

    @Test
    @DisplayName("UT-SF7-03: forecast = (total / 14) × 7 × 1.1 = total × 0.55")
    @SuppressWarnings("unchecked")
    void forecastFormula() throws Exception {
        SalesOrder so1 = new SalesOrder();
        so1.setId("so1");
        so1.setFactoryId(FACTORY_ID);
        so1.setOrderDate(LocalDate.now().minusDays(5));
        when(salesOrderRepository.findByFactoryIdAndDateRange(anyString(), any(), any()))
                .thenReturn(List.of(so1));

        SalesOrderItem item = new SalesOrderItem();
        item.setProductTypeId("P001");
        item.setProductName("卤猪蹄");
        item.setQuantity(new BigDecimal("140"));
        item.setUnit("kg");
        when(salesOrderItemRepository.findBySalesOrderId("so1")).thenReturn(List.of(item));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID, Map.of(), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<Map<String, Object>> forecasts = (List<Map<String, Object>>) data.get("forecasts");
        assertEquals(1, forecasts.size());
        Map<String, Object> row = forecasts.get(0);
        // 140 / 14 = 10 daily, * 7 = 70, * 1.1 = 77
        BigDecimal predicted = (BigDecimal) row.get("predicted7DayDemand");
        assertEquals(0, predicted.compareTo(new BigDecimal("77.00")),
                "Expected 77.00, got " + predicted);
    }

    @Test
    @DisplayName("UT-SF7-04: productTypeId filter limits to single product")
    @SuppressWarnings("unchecked")
    void productFilter() throws Exception {
        SalesOrder so1 = new SalesOrder();
        so1.setId("so1");
        so1.setFactoryId(FACTORY_ID);
        so1.setOrderDate(LocalDate.now().minusDays(3));
        when(salesOrderRepository.findByFactoryIdAndDateRange(anyString(), any(), any()))
                .thenReturn(List.of(so1));

        SalesOrderItem itemA = new SalesOrderItem();
        itemA.setProductTypeId("P001"); itemA.setProductName("猪蹄");
        itemA.setQuantity(new BigDecimal("50")); itemA.setUnit("kg");
        SalesOrderItem itemB = new SalesOrderItem();
        itemB.setProductTypeId("P002"); itemB.setProductName("鸡爪");
        itemB.setQuantity(new BigDecimal("30")); itemB.setUnit("kg");
        when(salesOrderItemRepository.findBySalesOrderId("so1")).thenReturn(List.of(itemA, itemB));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("productTypeId", "P001"), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<Map<String, Object>> forecasts = (List<Map<String, Object>>) data.get("forecasts");
        assertEquals(1, forecasts.size());
        assertEquals("P001", forecasts.get(0).get("productTypeId"));
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
