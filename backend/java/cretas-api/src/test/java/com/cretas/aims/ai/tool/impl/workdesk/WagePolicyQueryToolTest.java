package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.entity.WagePolicy;
import com.cretas.aims.entity.enums.WageMode;
import com.cretas.aims.repository.WagePolicyRepository;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Unit tests for {@link WagePolicyQueryTool} (Sprint 8 P2). */
@ExtendWith(MockitoExtension.class)
class WagePolicyQueryToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private WagePolicyQueryTool tool;

    @Mock
    private WagePolicyRepository wagePolicyRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-WPQ-01: metadata")
    void metadata() {
        assertEquals("wage_policy_query", tool.getToolName());
    }

    @Test
    @DisplayName("UT-WPQ-02: doExecute — lists factory-wide policies")
    @SuppressWarnings("unchecked")
    void listAll() throws Exception {
        WagePolicy p1 = WagePolicy.builder().id(1L).factoryId(FACTORY_ID)
                .employeeId(null).mode(WageMode.PIECE_RATE).isActive(true).build();
        WagePolicy p2 = WagePolicy.builder().id(2L).factoryId(FACTORY_ID)
                .employeeId(101L).mode(WageMode.HOURLY).isActive(true).build();
        when(wagePolicyRepository.findByFactoryIdOrderByEmployeeIdAscIdDesc(anyString()))
                .thenReturn(List.of(p1, p2));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID, Map.of(), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<Map<String, Object>> policies = (List<Map<String, Object>>) data.get("policies");
        assertEquals(2, policies.size());
        assertEquals("FACTORY_DEFAULT", policies.get(0).get("scope"));
        assertEquals("EMPLOYEE", policies.get(1).get("scope"));
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
