package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.dto.finance.report.BalanceSheetDTO;
import com.cretas.aims.entity.finance.AccountingPeriod;
import com.cretas.aims.service.finance.BalanceSheetService;
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

/** Unit tests for {@link BalanceSheetQueryTool} (Sprint 8 P2). */
@ExtendWith(MockitoExtension.class)
class BalanceSheetQueryToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private BalanceSheetQueryTool tool;

    @Mock
    private BalanceSheetService balanceSheetService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-BSQ-01: metadata")
    void metadata() {
        assertEquals("balance_sheet_query", tool.getToolName());
        assertTrue(tool.getRequiredParameters().isEmpty());
    }

    @Test
    @DisplayName("UT-BSQ-02: doExecute — returns balanced sheet with actionHint")
    @SuppressWarnings("unchecked")
    void happyPath() throws Exception {
        BalanceSheetDTO dto = BalanceSheetDTO.builder()
                .factoryId(FACTORY_ID).year(2026).month(5)
                .periodStatus(AccountingPeriod.Status.OPEN)
                .assets(List.of()).liabilities(List.of()).equity(List.of())
                .totalAssets(new BigDecimal("100000")).totalLiabilities(new BigDecimal("40000"))
                .totalEquity(new BigDecimal("60000"))
                .totalLiabilitiesAndEquity(new BigDecimal("100000"))
                .balanceCheck(true).generatedAt("2026-05-20T10:00:00").build();
        when(balanceSheetService.generate(anyString(), eq(2026), eq(5))).thenReturn(dto);

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("year", 2026, "month", 5), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(new BigDecimal("100000"), data.get("totalAssets"));
        assertEquals(true, data.get("balanceCheck"));
        assertTrue(data.get("actionHint").toString().contains("/finance/three-statements"));
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
