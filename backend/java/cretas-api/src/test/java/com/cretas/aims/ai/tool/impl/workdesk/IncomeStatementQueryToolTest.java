package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.dto.finance.report.IncomeStatementDTO;
import com.cretas.aims.dto.finance.report.BalanceSheetDTO;
import com.cretas.aims.service.finance.IncomeStatementService;
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
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/** Unit tests for {@link IncomeStatementQueryTool} (Sprint 8 P2). */
@ExtendWith(MockitoExtension.class)
class IncomeStatementQueryToolTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private IncomeStatementQueryTool tool;

    @Mock
    private IncomeStatementService incomeStatementService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        injectField(tool, "objectMapper", objectMapper);
    }

    @Test
    @DisplayName("UT-ISQ-01: metadata")
    void metadata() {
        assertEquals("income_statement_query", tool.getToolName());
        assertTrue(tool.getRequiredParameters().isEmpty());
    }

    @Test
    @DisplayName("UT-ISQ-02: doExecute — computes grossMarginPercent + actionHint")
    @SuppressWarnings("unchecked")
    void computesMargin() throws Exception {
        IncomeStatementDTO dto = IncomeStatementDTO.builder()
                .factoryId(FACTORY_ID).startYear(2026).startMonth(5).endYear(2026).endMonth(5)
                .costDataAvailable(true)
                .grossMarginStatus(IncomeStatementDTO.GrossMarginStatus.CALCULABLE)
                .revenues(List.of()).costs(List.of(BalanceSheetDTO.LineItem.builder()
                        .accountCode("5401").accountName("主营业务成本")
                        .amount(new BigDecimal("60000")).build())).expenses(List.of())
                .totalRevenue(new BigDecimal("100000"))
                .totalCost(new BigDecimal("60000"))
                .grossProfit(new BigDecimal("40000"))
                .totalExpense(new BigDecimal("10000"))
                .operatingProfit(new BigDecimal("30000"))
                .incomeTax(new BigDecimal("5000"))
                .netProfit(new BigDecimal("25000"))
                .generatedAt("2026-05-20T10:00:00").build();
        when(incomeStatementService.generate(anyString(), eq(2026), eq(5), eq(2026), eq(5)))
                .thenReturn(dto);

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("startYear", 2026, "startMonth", 5), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        // grossMarginPercent = 40000 / 100000 * 100 = 40.00
        assertEquals(0, ((BigDecimal) data.get("grossMarginPercent"))
                .compareTo(new BigDecimal("40.00")));
        assertEquals(0, ((BigDecimal) data.get("netMarginPercent"))
                .compareTo(new BigDecimal("25.00")));
        assertEquals("CALCULABLE", data.get("grossMarginStatus"));
        assertTrue(data.get("actionHint").toString().contains("income-statement"));
    }

    @Test
    @DisplayName("UT-ISQ-03: missing cost postings never become 0 or 100% margin")
    @SuppressWarnings("unchecked")
    void missingCostIsNotComputable() throws Exception {
        IncomeStatementDTO dto = IncomeStatementDTO.builder()
                .factoryId(FACTORY_ID).startYear(2026).startMonth(5).endYear(2026).endMonth(5)
                .costDataAvailable(false)
                .grossMarginStatus(IncomeStatementDTO.GrossMarginStatus.MISSING_COST_DATA)
                .grossMarginStatusMessage("期间没有已过账的营业成本分录，不能把缺失成本当作 0 计算毛利率")
                .revenues(List.of()).costs(List.of()).expenses(List.of())
                .totalRevenue(new BigDecimal("100000"))
                .totalCost(BigDecimal.ZERO)
                .grossProfit(new BigDecimal("100000"))
                .totalExpense(BigDecimal.ZERO)
                .operatingProfit(new BigDecimal("100000"))
                .incomeTax(BigDecimal.ZERO)
                .netProfit(new BigDecimal("100000"))
                .generatedAt("2026-05-20T10:00:00").build();
        when(incomeStatementService.generate(anyString(), eq(2026), eq(5), eq(2026), eq(5)))
                .thenReturn(dto);

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("startYear", 2026, "startMonth", 5), ctx());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertNull(data.get("totalCost"));
        assertNull(data.get("grossProfit"));
        assertNull(data.get("grossMarginPercent"));
        assertNull(data.get("operatingProfit"));
        assertNull(data.get("netProfit"));
        assertNull(data.get("netMarginPercent"));
        assertEquals("MISSING_COST_DATA", data.get("grossMarginStatus"));
        assertTrue(result.get("message").toString().contains("请先补录或同步成本凭证"));
    }

    @Test
    @DisplayName("UT-ISQ-04: natural-language last month is resolved instead of defaulting to current month")
    void resolvesNaturalLanguageLastMonth() {
        YearMonth[] period = IncomeStatementQueryTool.resolvePeriod(
                Map.of("userInput", "上月净利率是多少"),
                LocalDate.of(2026, 7, 20));

        assertArrayEquals(
                new YearMonth[]{YearMonth.of(2026, 6), YearMonth.of(2026, 6)},
                period);
    }

    @Test
    @DisplayName("UT-ISQ-05: preprocessed dates take priority over natural-language defaults")
    void resolvesPreprocessedDateRange() {
        YearMonth[] period = IncomeStatementQueryTool.resolvePeriod(
                Map.of(
                        "userInput", "查看净利率",
                        "startDate", "2025-11-01",
                        "endDate", "2026-01-31"),
                LocalDate.of(2026, 7, 20));

        assertArrayEquals(
                new YearMonth[]{YearMonth.of(2025, 11), YearMonth.of(2026, 1)},
                period);
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

    @Test
    @DisplayName("UT-ISQ-EMPTY: all-zero period → targeted no-data message, never a zero dump")
    @SuppressWarnings("unchecked")
    void allZeroPeriodDeclinesInsteadOfZeroDump() throws Exception {
        IncomeStatementDTO dto = IncomeStatementDTO.builder()
                .factoryId(FACTORY_ID).startYear(2026).startMonth(7).endYear(2026).endMonth(7)
                .costDataAvailable(true)
                .grossMarginStatus(IncomeStatementDTO.GrossMarginStatus.NO_REVENUE)
                .grossMarginStatusMessage("期间营业收入为零或缺失，毛利率不可计算")
                .revenues(List.of()).costs(List.of()).expenses(List.of())
                .totalRevenue(BigDecimal.ZERO)
                .totalCost(BigDecimal.ZERO)
                .grossProfit(BigDecimal.ZERO)
                .totalExpense(BigDecimal.ZERO)
                .operatingProfit(BigDecimal.ZERO)
                .incomeTax(BigDecimal.ZERO)
                .netProfit(BigDecimal.ZERO)
                .generatedAt("2026-07-22T10:00:00").build();
        when(incomeStatementService.generate(anyString(), eq(2026), eq(7), eq(2026), eq(7)))
                .thenReturn(dto);

        Map<String, Object> result = invoke("doExecute", FACTORY_ID,
                Map.of("startYear", 2026, "startMonth", 7), ctx());
        String message = (String) result.get("message");
        assertTrue(message.contains("暂无已过账的利润表数据"), message);
        assertTrue(message.contains("不会用零值替代"), message);
        assertFalse(message.contains("¥0"), message);
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(Boolean.TRUE, data.get("noPostedData"));
    }
}
