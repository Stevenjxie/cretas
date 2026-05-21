package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.ShipmentRecord;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ShipmentRecordRepository;
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

/** Unit tests for {@link BatchTraceByCustomerDateTool} (Sprint 8 P3 Phase B). */
@ExtendWith(MockitoExtension.class)
class BatchTraceByCustomerDateToolTest {

    private static final String FACTORY_ID = "F006";
    private static final String CUSTOMER_ID = "cust-001";

    @InjectMocks
    private BatchTraceByCustomerDateTool tool;

    @Mock
    private ShipmentRecordRepository shipmentRecordRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-BTCD-01: metadata + tool name")
    void metadata() {
        assertEquals("batch_trace_by_customer_date", tool.getToolName());
        assertNotNull(tool.getDescription());
        assertTrue(tool.getDescription().contains("反查"));
        assertEquals(List.of("customerId", "date"), tool.getRequiredParameters());
    }

    @Test
    @DisplayName("UT-BTCD-02: doExecute — finds shipments on date + extracts batch numbers")
    @SuppressWarnings("unchecked")
    void findsShipmentsOnDate() throws Exception {
        LocalDate target = LocalDate.of(2026, 5, 18);

        Customer cust = new Customer();
        cust.setId(CUSTOMER_ID);
        cust.setName("鲜湘缘餐厅");
        when(customerRepository.findByIdAndFactoryId(CUSTOMER_ID, FACTORY_ID))
                .thenReturn(Optional.of(cust));

        ShipmentRecord s1 = new ShipmentRecord();
        s1.setShipmentNumber("SH-001");
        s1.setBatchNumber("B-20260518-A03");
        s1.setProductName("卤猪蹄");
        s1.setQuantity(new BigDecimal("50"));
        s1.setUnit("kg");
        s1.setShipmentDate(target);

        ShipmentRecord s2 = new ShipmentRecord();
        s2.setShipmentNumber("SH-002");
        s2.setBatchNumber("B-20260517-A01"); // 不同日期, 应被过滤
        s2.setShipmentDate(target.minusDays(1));

        when(shipmentRecordRepository.findByFactoryIdAndCustomerIdOrderByShipmentDateDesc(
                FACTORY_ID, CUSTOMER_ID))
                .thenReturn(List.of(s1, s2));

        Map<String, Object> params = Map.of("customerId", CUSTOMER_ID, "date", target.toString());
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(1, data.get("shipmentCount"));
        List<String> batchNumbers = (List<String>) data.get("batchNumbers");
        assertEquals(1, batchNumbers.size());
        assertEquals("B-20260518-A03", batchNumbers.get(0));
        assertEquals("鲜湘缘餐厅", data.get("customerName"));
    }

    @Test
    @DisplayName("UT-BTCD-03: doExecute — no shipments on date returns empty + actionHint")
    @SuppressWarnings("unchecked")
    void noShipmentsReturnsEmpty() throws Exception {
        when(customerRepository.findByIdAndFactoryId(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(shipmentRecordRepository.findByFactoryIdAndCustomerIdOrderByShipmentDateDesc(
                anyString(), anyString()))
                .thenReturn(List.of());

        Map<String, Object> params = Map.of("customerId", CUSTOMER_ID, "date", "2026-05-18");
        Map<String, Object> result = invoke("doExecute", FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(0, data.get("shipmentCount"));
        assertNotNull(data.get("actionHint"));
        assertTrue(data.get("actionHint").toString().contains(CUSTOMER_ID));
    }

    @Test
    @DisplayName("UT-BTCD-04: doExecute — invalid date format throws IllegalArgumentException")
    void invalidDateThrows() {
        Map<String, Object> params = Map.of("customerId", CUSTOMER_ID, "date", "BAD-DATE");
        assertThrows(IllegalArgumentException.class,
                () -> invoke("doExecute", FACTORY_ID, params, ctx()));
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
