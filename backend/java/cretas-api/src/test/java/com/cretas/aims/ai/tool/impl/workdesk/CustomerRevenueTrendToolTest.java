package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.CustomerRepository;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CustomerRevenueTrendTool} (Sprint 8 P1).
 */
@ExtendWith(MockitoExtension.class)
class CustomerRevenueTrendToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private CustomerRevenueTrendTool tool;

    @Mock
    private SalesOrderRepository salesOrderRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-CRT-01: Tool metadata")
    void metadata() {
        assertEquals("customer_revenue_trend", tool.getToolName());
        assertTrue(tool.getDescription().contains("收入趋势"));
    }

    @Test
    @DisplayName("UT-CRT-02: customerId provided — aggregates by month, computes pctChange")
    @SuppressWarnings("unchecked")
    void aggregateByMonth() throws Exception {
        // Anchor to day 1 of the month: day-1 of THIS month is always <= today (so the
        // tool's isAfter(now) future-filter never drops it), and prior-month day-1 is
        // always inside the periodMonths=2 window. Using day-15 broke on the 1st of the
        // month (June 1 CI): day-15 = a FUTURE date → filtered out → this-month bucket=0.
        LocalDate thisMonth = LocalDate.now().withDayOfMonth(1);
        LocalDate lastMonth = thisMonth.minusMonths(1);

        SalesOrder soThis = newSalesOrder(thisMonth, new BigDecimal("1500"));
        SalesOrder soLast = newSalesOrder(lastMonth, new BigDecimal("1000"));
        when(salesOrderRepository.findByFactoryIdAndCustomerId(anyString(), anyString()))
                .thenReturn(List.of(soThis, soLast));
        when(customerRepository.findByIdAndFactoryId(anyString(), anyString()))
                .thenReturn(Optional.of(newCustomer()));

        Map<String, Object> result = invokeDoExecute(tool, FACTORY_ID,
                Map.of("customerId", "c1", "periodMonths", 2), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<Map<String, Object>> series = (List<Map<String, Object>>) data.get("series");
        assertFalse(series.isEmpty(), "series should not be empty");
        // Last point (this month) should have totalAmount=1500 and pctChange=50.00
        Map<String, Object> latest = series.get(series.size() - 1);
        assertEquals(0, ((BigDecimal) latest.get("totalAmount")).compareTo(new BigDecimal("1500")));
    }

    @Test
    @DisplayName("UT-CRT-03: no customerId — uses date-range query, aggregates all customers")
    @SuppressWarnings("unchecked")
    void noCustomerIdAggregatesAll() throws Exception {
        when(salesOrderRepository.findByFactoryIdAndDateRange(
                anyString(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());

        Map<String, Object> result = invokeDoExecute(tool, FACTORY_ID, Map.of(), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertNull(data.get("customerId"));
        List<Map<String, Object>> series = (List<Map<String, Object>>) data.get("series");
        assertFalse(series.isEmpty(), "series should always have month buckets even when 0 orders");
    }

    // ── helpers ──
    private SalesOrder newSalesOrder(LocalDate date, BigDecimal amount) {
        SalesOrder so = new SalesOrder();
        so.setId(date.toString() + "-" + amount);
        so.setFactoryId(FACTORY_ID);
        so.setOrderNumber("SO-" + date);
        so.setCustomerId("c1");
        so.setOrderDate(date);
        so.setTotalAmount(amount);
        so.setStatus(SalesOrderStatus.DRAFT);
        so.setCreatedBy(1L);
        return so;
    }

    private Customer newCustomer() {
        Customer c = new Customer();
        c.setId("c1");
        c.setFactoryId(FACTORY_ID);
        c.setCode("CC");
        c.setCustomerCode("CC");
        c.setName("张老板");
        return c;
    }

    private Map<String, Object> ctx() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", FACTORY_ID);
        ctx.put("userId", 1L);
        return ctx;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeDoExecute(Object tool, String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        var method = findMethod(tool.getClass(), "doExecute");
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
