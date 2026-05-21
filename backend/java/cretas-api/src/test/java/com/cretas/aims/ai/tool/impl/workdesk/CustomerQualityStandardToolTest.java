package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.entity.Customer;
import com.cretas.aims.repository.CustomerRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/** Unit tests for {@link CustomerQualityStandardTool} (Sprint 8 P4c). */
@ExtendWith(MockitoExtension.class)
class CustomerQualityStandardToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private CustomerQualityStandardTool tool;

    @Mock
    private CustomerRepository customerRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-CQS-01: metadata — name + required + description")
    void metadata() {
        assertEquals("customer_quality_standard", tool.getToolName());
        assertTrue(tool.getDescription().contains("客户质量标准"));
        assertEquals(List.of("customerId"), tool.getRequiredParameters());
    }

    @Test
    @DisplayName("UT-CQS-02: customer not found — fallback to FACTORY_DEFAULT + actionHint")
    @SuppressWarnings("unchecked")
    void customerNotFound() throws Exception {
        when(customerRepository.findByIdAndFactoryId("c-x", FACTORY_ID))
                .thenReturn(Optional.empty());

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("customerId", "c-x"), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(false, data.get("customerFound"));
        assertEquals("FACTORY_DEFAULT", data.get("source"));
        assertNotNull(data.get("standards"));
        assertNotNull(data.get("actionHint"));
    }

    @Test
    @DisplayName("UT-CQS-03: customer with quality keywords in notes — CUSTOMER_NOTES_PLUS")
    @SuppressWarnings("unchecked")
    void customerWithStandardKeywords() throws Exception {
        Customer customer = new Customer();
        customer.setId("c-y");
        customer.setFactoryId(FACTORY_ID);
        customer.setName("鲜湘缘");
        customer.setNotes("客户质量要求: 中心温度 ≥ 78°C, COA 必备");
        when(customerRepository.findByIdAndFactoryId("c-y", FACTORY_ID))
                .thenReturn(Optional.of(customer));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("customerId", "c-y"), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(true, data.get("customerFound"));
        assertEquals("CUSTOMER_NOTES_PLUS_FACTORY_DEFAULT", data.get("source"));
        assertNotNull(data.get("customerSpecificText"));
    }

    @Test
    @DisplayName("UT-CQS-04: customer without standards — R5 dead-end + actionHint")
    @SuppressWarnings("unchecked")
    void customerWithoutStandards() throws Exception {
        Customer customer = new Customer();
        customer.setId("c-z");
        customer.setFactoryId(FACTORY_ID);
        customer.setName("某客户");
        customer.setNotes("VIP 客户, 长期合作");
        when(customerRepository.findByIdAndFactoryId("c-z", FACTORY_ID))
                .thenReturn(Optional.of(customer));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("customerId", "c-z"), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(true, data.get("customerFound"));
        assertEquals("FACTORY_DEFAULT", data.get("source"));
        assertNotNull(data.get("actionHint"));
        assertTrue(((String) data.get("actionHint")).contains("/crm/customers/"));
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
