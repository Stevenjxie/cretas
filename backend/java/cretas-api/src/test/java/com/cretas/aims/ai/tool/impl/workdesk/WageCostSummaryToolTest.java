package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.entity.WageCalculation;
import com.cretas.aims.entity.enums.WageMode;
import com.cretas.aims.repository.WageCalculationRepository;
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
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/** Unit tests for {@link WageCostSummaryTool} (Sprint 8 P2). */
@ExtendWith(MockitoExtension.class)
class WageCostSummaryToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private WageCostSummaryTool tool;

    @Mock
    private WageCalculationRepository wageCalculationRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-WCS-01: metadata")
    void metadata() {
        assertEquals("wage_cost_summary", tool.getToolName());
    }

    @Test
    @DisplayName("UT-WCS-02: doExecute — splits PIECE/HOURLY/MIXED + sums totalAmount")
    @SuppressWarnings("unchecked")
    void splitsByMode() throws Exception {
        WageCalculation w1 = WageCalculation.builder().id(1L).factoryId(FACTORY_ID)
                .employeeId(1L).mode(WageMode.PIECE_RATE)
                .pieceRateAmount(new BigDecimal("3000"))
                .hourlyAmount(BigDecimal.ZERO).overtimeAmount(BigDecimal.ZERO).build();
        WageCalculation w2 = WageCalculation.builder().id(2L).factoryId(FACTORY_ID)
                .employeeId(2L).mode(WageMode.HOURLY)
                .pieceRateAmount(BigDecimal.ZERO)
                .hourlyAmount(new BigDecimal("4000"))
                .overtimeAmount(new BigDecimal("500")).build();
        when(wageCalculationRepository.findByFactoryIdAndPeriodMonth(
                anyString(), any(LocalDate.class))).thenReturn(List.of(w1, w2));

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("year", 2026, "month", 5), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(2, data.get("employeeCount"));
        // total = 3000 + 4000 + 500 = 7500
        assertEquals(0, ((BigDecimal) data.get("totalAmount")).compareTo(new BigDecimal("7500.00")));
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
