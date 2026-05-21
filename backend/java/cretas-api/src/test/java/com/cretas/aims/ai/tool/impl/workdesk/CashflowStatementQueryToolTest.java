package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.dto.finance.report.CashFlowDTO;
import com.cretas.aims.service.finance.CashFlowService;
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

/** Unit tests for {@link CashflowStatementQueryTool} (Sprint 8 P2). */
@ExtendWith(MockitoExtension.class)
class CashflowStatementQueryToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private CashflowStatementQueryTool tool;

    @Mock
    private CashFlowService cashFlowService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-CSQ-01: metadata")
    void metadata() {
        assertEquals("cashflow_statement_query", tool.getToolName());
    }

    @Test
    @DisplayName("UT-CSQ-02: doExecute — returns 3-activity cashflow")
    @SuppressWarnings("unchecked")
    void happyPath() throws Exception {
        CashFlowDTO dto = CashFlowDTO.builder()
                .factoryId(FACTORY_ID).startYear(2026).startMonth(5).endYear(2026).endMonth(5)
                .operatingActivities(List.of())
                .investingActivities(List.of())
                .financingActivities(List.of())
                .operatingNetCashFlow(new BigDecimal("20000"))
                .investingNetCashFlow(new BigDecimal("-5000"))
                .financingNetCashFlow(new BigDecimal("10000"))
                .netIncreaseInCash(new BigDecimal("25000"))
                .beginningCash(new BigDecimal("0"))
                .endingCash(new BigDecimal("25000"))
                .generatedAt("2026-05-20T10:00:00").build();
        when(cashFlowService.generate(anyString(), eq(2026), eq(5), eq(2026), eq(5)))
                .thenReturn(dto);

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("startYear", 2026, "startMonth", 5), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(new BigDecimal("25000"), data.get("netIncreaseInCash"));
        assertTrue(data.get("actionHint").toString().contains("cashflow"));
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
