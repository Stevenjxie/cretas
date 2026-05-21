package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.CustomerQualityStandard;
import com.cretas.aims.repository.CustomerQualityStandardRepository;
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
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Unit tests for {@link CustomerQualityStandardTool} (Sprint 8 P4c + Sprint 9 P1.1). */
@ExtendWith(MockitoExtension.class)
class CustomerQualityStandardToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private CustomerQualityStandardTool tool;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private CustomerQualityStandardRepository customerQualityStandardRepository;

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
    @DisplayName("UT-CQS-03: Sprint 9 — registered CustomerQualityStandard rows take precedence")
    @SuppressWarnings("unchecked")
    void registeredStandardsTakePrecedence() throws Exception {
        Customer customer = new Customer();
        customer.setId("c-r");
        customer.setFactoryId(FACTORY_ID);
        customer.setName("鲜湘缘");
        // 注意: 即便 notes 有 "质量" 关键字, 也应走 CUSTOMER_REGISTERED 优先
        customer.setNotes("客户质量要求: COA 必备");
        when(customerRepository.findByIdAndFactoryId("c-r", FACTORY_ID))
                .thenReturn(Optional.of(customer));

        CustomerQualityStandard s1 = CustomerQualityStandard.builder()
                .factoryId(FACTORY_ID)
                .customerId("c-r")
                .standardCode("CQS-A01")
                .standardName("微生物总数 ≤ 10^4")
                .category("BIOLOGICAL")
                .limitValue(new BigDecimal("10000.0000"))
                .unit("CFU/g")
                .active(true)
                .build();
        CustomerQualityStandard s2 = CustomerQualityStandard.builder()
                .factoryId(FACTORY_ID)
                .customerId("c-r")
                .standardCode("CQS-A02")
                .standardName("中心温度 ≥ 78°C")
                .category("PHYSICAL")
                .limitValue(new BigDecimal("78.0000"))
                .unit("°C")
                .active(true)
                .build();
        when(customerQualityStandardRepository
                .findByFactoryIdAndCustomerIdAndActiveTrue(FACTORY_ID, "c-r"))
                .thenReturn(List.of(s1, s2));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("customerId", "c-r"), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(true, data.get("customerFound"));
        assertEquals("CUSTOMER_REGISTERED", data.get("source"));
        assertEquals(2, data.get("registeredCount"));

        List<Map<String, Object>> standards = (List<Map<String, Object>>) data.get("standards");
        assertEquals(2, standards.size());
        assertEquals("CQS-A01", standards.get(0).get("standardCode"));
        assertEquals("微生物总数 ≤ 10^4", standards.get(0).get("name"));
        assertEquals("BIOLOGICAL", standards.get(0).get("category"));
        assertEquals("CFU/g", standards.get(0).get("unit"));
    }

    @Test
    @DisplayName("UT-CQS-04: customer with quality keywords in notes — CUSTOMER_NOTES_PLUS (Sprint 8 backwards-compat)")
    @SuppressWarnings("unchecked")
    void customerWithStandardKeywords() throws Exception {
        Customer customer = new Customer();
        customer.setId("c-y");
        customer.setFactoryId(FACTORY_ID);
        customer.setName("鲜湘缘");
        customer.setNotes("客户质量要求: 中心温度 ≥ 78°C, COA 必备");
        when(customerRepository.findByIdAndFactoryId("c-y", FACTORY_ID))
                .thenReturn(Optional.of(customer));
        // 关键: registered 返回 empty → fallback to notes
        when(customerQualityStandardRepository
                .findByFactoryIdAndCustomerIdAndActiveTrue(FACTORY_ID, "c-y"))
                .thenReturn(List.of());

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("customerId", "c-y"), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(true, data.get("customerFound"));
        assertEquals("CUSTOMER_NOTES_PLUS_FACTORY_DEFAULT", data.get("source"));
        assertNotNull(data.get("customerSpecificText"));
    }

    @Test
    @DisplayName("UT-CQS-05: customer without standards — R5 dead-end + actionHint")
    @SuppressWarnings("unchecked")
    void customerWithoutStandards() throws Exception {
        Customer customer = new Customer();
        customer.setId("c-z");
        customer.setFactoryId(FACTORY_ID);
        customer.setName("某客户");
        customer.setNotes("VIP 客户, 长期合作");
        when(customerRepository.findByIdAndFactoryId("c-z", FACTORY_ID))
                .thenReturn(Optional.of(customer));
        when(customerQualityStandardRepository
                .findByFactoryIdAndCustomerIdAndActiveTrue(FACTORY_ID, "c-z"))
                .thenReturn(List.of());

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
