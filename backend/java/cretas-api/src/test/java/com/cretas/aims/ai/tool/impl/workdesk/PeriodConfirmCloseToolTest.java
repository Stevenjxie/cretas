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
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/** Unit tests for {@link PeriodConfirmCloseTool} (Sprint 8 P2). */
@ExtendWith(MockitoExtension.class)
class PeriodConfirmCloseToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private PeriodConfirmCloseTool tool;

    @Mock
    private AccountingPeriodService accountingPeriodService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-PCC-01: metadata — WRITE / MEDIUM / supportsPreview")
    void metadata() {
        assertEquals("period_confirm_close", tool.getToolName());
        assertEquals(ToolExecutor.ActionType.WRITE, tool.getActionType());
        assertTrue(tool.supportsPreview());
    }

    @Test
    @DisplayName("UT-PCC-02: doPreview — PENDING_CLOSE → PREVIEW + BLOCKING warning")
    void previewBlocking() throws Exception {
        when(accountingPeriodService.getStatus(anyString(), eq(2026), eq(5)))
                .thenReturn(AccountingPeriod.Status.PENDING_CLOSE);

        Map<String, Object> result = invoke("doPreview", FACTORY_ID,
                Map.of("year", 2026, "month", 5), ctx());
        assertEquals("PREVIEW", result.get("status"));
        assertEquals(true, result.get("canDo"));
        assertEquals(true, result.get("blocking"));
        assertTrue(result.get("message").toString().contains("BLOCKING"));
    }

    @Test
    @DisplayName("UT-PCC-03: doPreview — OPEN state → INVALID_STATE hint period_request_close")
    void previewInvalidWhenOpen() throws Exception {
        when(accountingPeriodService.getStatus(anyString(), eq(2026), eq(5)))
                .thenReturn(AccountingPeriod.Status.OPEN);

        Map<String, Object> result = invoke("doPreview", FACTORY_ID,
                Map.of("year", 2026, "month", 5), ctx());
        assertEquals("INVALID_STATE", result.get("status"));
        assertTrue(result.get("message").toString().contains("period_request_close"));
    }

    @Test
    @DisplayName("UT-PCC-04: doExecute — happy path returns CLOSED")
    void executeHappyPath() throws Exception {
        AccountingPeriod p = AccountingPeriod.builder()
                .id("p1").factoryId(FACTORY_ID).year(2026).month(5)
                .status(AccountingPeriod.Status.CLOSED)
                .closedAt(LocalDateTime.now()).closedBy(1L).build();
        when(accountingPeriodService.confirmClose(anyString(), eq(2026), eq(5), eq(1L)))
                .thenReturn(p);

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("year", 2026, "month", 5), ctx());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals("CLOSED", data.get("status"));
        assertTrue(result.get("message").toString().contains("已关账"));
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
