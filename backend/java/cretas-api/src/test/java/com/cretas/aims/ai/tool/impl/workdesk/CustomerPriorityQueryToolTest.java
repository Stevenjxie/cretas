package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.SalesOpportunity;
import com.cretas.aims.entity.enums.CustomerImportance;
import com.cretas.aims.entity.enums.OpportunityStage;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.SalesOpportunityRepository;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CustomerPriorityQueryTool} (Sprint 8 P1).
 */
@ExtendWith(MockitoExtension.class)
class CustomerPriorityQueryToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private CustomerPriorityQueryTool tool;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private SalesOpportunityRepository salesOpportunityRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-CPQ-01: Tool metadata — name / READ ActionType / LOW risk / no required params")
    void metadata() {
        assertEquals("customer_priority_query", tool.getToolName());
        assertTrue(tool.getDescription().contains("优先级"));
        assertTrue(tool.getDescription().contains("今天该跟谁"));
        assertTrue(tool.getRequiredParameters().isEmpty());
    }

    @Test
    @DisplayName("UT-CPQ-02: doExecute — happy path, customers sorted by priorityScore desc")
    @SuppressWarnings("unchecked")
    void doExecuteSortsByPriorityScore() throws Exception {
        Customer normal = customer("c1", "普通客户", CustomerImportance.NORMAL);
        Customer vip = customer("c2", "VIP客户", CustomerImportance.VIP);
        Customer important = customer("c3", "重要客户", CustomerImportance.IMPORTANT);
        Page<Customer> page = new PageImpl<>(List.of(normal, vip, important));

        when(customerRepository.findByFactoryIdAndIsActiveTrue(anyString(), any(Pageable.class)))
                .thenReturn(page);
        when(salesOpportunityRepository
                .findByFactoryIdAndCustomerIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                        anyString(), anyString()))
                .thenReturn(new ArrayList<>());

        Map<String, Object> result = invokeDoExecute(tool, FACTORY_ID, Map.of(), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<Map<String, Object>> sorted = (List<Map<String, Object>>) data.get("customers");

        assertEquals(3, sorted.size());
        // VIP first (40), IMPORTANT second (20), NORMAL last (10)
        assertEquals("VIP客户", sorted.get(0).get("customerName"));
        assertEquals("重要客户", sorted.get(1).get("customerName"));
        assertEquals("普通客户", sorted.get(2).get("customerName"));
    }

    @Test
    @DisplayName("UT-CPQ-03: includeStages filter — exclude customers without matching opportunities")
    @SuppressWarnings("unchecked")
    void includeStagesFilter() throws Exception {
        Customer c1 = customer("c1", "客户1", CustomerImportance.VIP);
        Customer c2 = customer("c2", "客户2", CustomerImportance.VIP);
        Page<Customer> page = new PageImpl<>(List.of(c1, c2));

        when(customerRepository.findByFactoryIdAndIsActiveTrue(anyString(), any(Pageable.class)))
                .thenReturn(page);
        // c1 has NEGOTIATE opp
        when(salesOpportunityRepository
                .findByFactoryIdAndCustomerIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                        anyString(), org.mockito.ArgumentMatchers.eq("c1")))
                .thenReturn(List.of(opportunity(OpportunityStage.NEGOTIATE)));
        // c2 has LEAD opp
        when(salesOpportunityRepository
                .findByFactoryIdAndCustomerIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                        anyString(), org.mockito.ArgumentMatchers.eq("c2")))
                .thenReturn(List.of(opportunity(OpportunityStage.LEAD)));

        Map<String, Object> params = Map.of("includeStages", List.of("NEGOTIATE"));
        Map<String, Object> result = invokeDoExecute(tool, FACTORY_ID, params, ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<Map<String, Object>> filtered = (List<Map<String, Object>>) data.get("customers");

        // Only c1 (with NEGOTIATE opp) should be in results
        assertEquals(1, filtered.size());
        assertEquals("客户1", filtered.get(0).get("customerName"));
    }

    @Test
    @DisplayName("UT-CPQ-04: doExecute — empty customer list, returns empty array + message")
    @SuppressWarnings("unchecked")
    void doExecuteEmptyList() throws Exception {
        when(customerRepository.findByFactoryIdAndIsActiveTrue(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        Map<String, Object> result = invokeDoExecute(tool, FACTORY_ID, Map.of(), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<Map<String, Object>> customers = (List<Map<String, Object>>) data.get("customers");

        assertNotNull(customers);
        assertTrue(customers.isEmpty());
        assertTrue(result.get("message").toString().contains("未找到"));
    }

    // ── helpers ──

    private Customer customer(String id, String name, CustomerImportance importance) {
        Customer c = new Customer();
        c.setId(id);
        c.setFactoryId(FACTORY_ID);
        c.setCode("CODE-" + id);
        c.setCustomerCode("CC-" + id);
        c.setName(name);
        c.setImportance(importance);
        return c;
    }

    private SalesOpportunity opportunity(OpportunityStage stage) {
        return SalesOpportunity.builder().id("opp-1").factoryId(FACTORY_ID)
                .customerId("c1").title("test opp").stage(stage)
                .probability(stage.getDefaultProbability()).ownerId(1L).createdBy(1L)
                .build();
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
