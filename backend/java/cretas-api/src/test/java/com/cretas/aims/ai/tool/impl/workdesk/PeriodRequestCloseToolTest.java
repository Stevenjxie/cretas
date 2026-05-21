package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.enums.VoucherStatus;
import com.cretas.aims.entity.finance.AccountingPeriod;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.repository.VoucherRepository;
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
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/** Unit tests for {@link PeriodRequestCloseTool} (Sprint 8 P2). */
@ExtendWith(MockitoExtension.class)
class PeriodRequestCloseToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private PeriodRequestCloseTool tool;

    @Mock
    private AccountingPeriodService accountingPeriodService;

    @Mock
    private VoucherRepository voucherRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-PRC-01: metadata — WRITE / MEDIUM / supportsPreview / 2 required")
    void metadata() {
        assertEquals("period_request_close", tool.getToolName());
        assertEquals(ToolExecutor.ActionType.WRITE, tool.getActionType());
        assertEquals(ToolExecutor.RiskLevel.MEDIUM, tool.getRiskLevel());
        assertTrue(tool.supportsPreview());
        assertEquals(2, tool.getRequiredParameters().size());
    }

    @Test
    @DisplayName("UT-PRC-02: doPreview — current OPEN → PREVIEW with voucher counts (R1)")
    void previewOpen() throws Exception {
        when(accountingPeriodService.getStatus(anyString(), eq(2026), eq(5)))
                .thenReturn(AccountingPeriod.Status.OPEN);
        Voucher v1 = Voucher.builder().id("v1").status(VoucherStatus.DRAFT)
                .voucherDate(LocalDate.of(2026, 5, 10)).build();
        Voucher v2 = Voucher.builder().id("v2").status(VoucherStatus.POSTED)
                .voucherDate(LocalDate.of(2026, 5, 15)).build();
        when(voucherRepository.findByFactoryIdAndDateRange(anyString(), any(), any()))
                .thenReturn(List.of(v1, v2));

        Map<String, Object> result = invoke("doPreview", FACTORY_ID,
                Map.of("year", 2026, "month", 5), ctx());
        assertEquals("PREVIEW", result.get("status"));
        assertEquals(true, result.get("canDo"));
        assertEquals(2, result.get("voucherCount"));
        assertEquals(1L, result.get("draftCount"));
    }

    @Test
    @DisplayName("UT-PRC-03: doPreview — current CLOSED → INVALID_STATE, canDo=false")
    void previewInvalidState() throws Exception {
        when(accountingPeriodService.getStatus(anyString(), eq(2026), eq(5)))
                .thenReturn(AccountingPeriod.Status.CLOSED);
        when(voucherRepository.findByFactoryIdAndDateRange(anyString(), any(), any()))
                .thenReturn(List.of());

        Map<String, Object> result = invoke("doPreview", FACTORY_ID,
                Map.of("year", 2026, "month", 5), ctx());
        assertEquals("INVALID_STATE", result.get("status"));
        assertEquals(false, result.get("canDo"));
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
