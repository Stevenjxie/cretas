package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.service.finance.ArApService;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/** Unit tests for {@link AccountsReceivableAgingTool} (Sprint 8 P2). */
@ExtendWith(MockitoExtension.class)
class AccountsReceivableAgingToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private AccountsReceivableAgingTool tool;

    @Mock
    private ArApService arApService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-ARA-01: metadata")
    void metadata() {
        assertEquals("accounts_receivable_aging", tool.getToolName());
    }

    @Test
    @DisplayName("UT-ARA-02: doExecute — sums + extracts highRisk (60+ days)")
    @SuppressWarnings("unchecked")
    void detectsHighRisk() throws Exception {
        Map<String, Object> b1 = Map.of("bucket", "0-30", "amount", new BigDecimal("10000"), "count", 5L);
        Map<String, Object> b2 = Map.of("bucket", "61-90", "amount", new BigDecimal("3000"), "count", 2L);
        Map<String, Object> b3 = Map.of("bucket", "91-180", "amount", new BigDecimal("5000"), "count", 1L);
        when(arApService.getAgingAnalysis(anyString(), eq(CounterpartyType.CUSTOMER)))
                .thenReturn(List.of(b1, b2, b3));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID, Map.of(), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        // 0-30 not high risk, 61-90 + 91-180 are high risk
        assertEquals(3L, data.get("highRiskCount"));
        assertEquals(0, ((BigDecimal) data.get("highRiskAmount"))
                .compareTo(new BigDecimal("8000")));
        assertEquals(8L, data.get("totalCount"));
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
