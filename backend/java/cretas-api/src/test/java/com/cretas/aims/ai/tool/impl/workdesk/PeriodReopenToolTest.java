package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.finance.AccountingPeriod;
import com.cretas.aims.service.finance.AccountingPeriodService;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/** Unit tests for {@link PeriodReopenTool} (Sprint 8 P2). */
@ExtendWith(MockitoExtension.class)
class PeriodReopenToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private PeriodReopenTool tool;

    @Mock
    private AccountingPeriodService accountingPeriodService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-PRO-01: metadata — WRITE / MEDIUM / preview / 3 required")
    void metadata() {
        assertEquals("period_reopen", tool.getToolName());
        assertEquals(ToolExecutor.ActionType.WRITE, tool.getActionType());
        assertEquals(3, tool.getRequiredParameters().size());
        assertTrue(tool.getRequiredParameters().contains("reason"));
    }

    @Test
    @DisplayName("UT-PRO-02: doPreview — CLOSED → PREVIEW + audit warning")
    void previewAuditWarning() throws Exception {
        when(accountingPeriodService.getStatus(anyString(), eq(2026), eq(5)))
                .thenReturn(AccountingPeriod.Status.CLOSED);

        Map<String, Object> result = invoke("doPreview", FACTORY_ID,
                Map.of("year", 2026, "month", 5, "reason", "补漏一笔工资凭证"), ctx());
        assertEquals("PREVIEW", result.get("status"));
        assertEquals(true, result.get("canDo"));
        assertEquals(true, result.get("auditWarning"));
        assertTrue(result.get("message").toString().contains("AUDIT"));
    }

    @Test
    @DisplayName("UT-PRO-03: doExecute — empty reason throws")
    void executeEmptyReason() {
        assertThrows(IllegalArgumentException.class,
                () -> invoke("doExecute", FACTORY_ID,
                        Map.of("year", 2026, "month", 5, "reason", ""), ctx()));
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
