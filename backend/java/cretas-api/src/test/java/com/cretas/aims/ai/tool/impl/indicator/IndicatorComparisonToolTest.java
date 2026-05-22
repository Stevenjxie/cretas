package com.cretas.aims.ai.tool.impl.indicator;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.indicator.Indicator;
import com.cretas.aims.entity.indicator.IndicatorThreshold;
import com.cretas.aims.entity.indicator.IndicatorVersion;
import com.cretas.aims.repository.indicator.IndicatorRepository;
import com.cretas.aims.repository.indicator.IndicatorThresholdRepository;
import com.cretas.aims.repository.indicator.IndicatorVersionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;

/** Unit tests for {@link IndicatorComparisonTool} (Sprint 11 D4). */
@ExtendWith(MockitoExtension.class)
class IndicatorComparisonToolTest {

    private static final String FACTORY_ID = "F999_MOCK";

    @InjectMocks
    private IndicatorComparisonTool tool;

    @Mock
    private IndicatorRepository indicatorRepository;

    @Mock
    private IndicatorVersionRepository versionRepository;

    @Mock
    private IndicatorThresholdRepository thresholdRepository;

    @Test
    @DisplayName("UT-ICT-01: metadata — READ + LOW + tool_name=indicator_comparison")
    void metadata() {
        assertEquals("indicator_comparison", tool.getToolName());
        assertEquals(ToolExecutor.ActionType.READ, tool.getActionType());
        assertEquals(ToolExecutor.RiskLevel.LOW, tool.getRiskLevel());
        Map<String, Object> schema = tool.getParametersSchema();
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertEquals(List.of("indicator_codes"), required);
    }

    @Test
    @DisplayName("UT-ICT-02: 3 indicators, 1 RED + 2 GREEN — worst=RED")
    @SuppressWarnings("unchecked")
    void happyPathWithRed() throws Exception {
        // RED indicator: 良品率 88 with RED threshold LT 90
        mockIndicator("FACTORY_YIELD_RATE", "ind-yield", "良品率", "FACTORY", "%",
                new BigDecimal("88.00"),
                List.of(buildThreshold("RED", "LT", "90"),
                        buildThreshold("GREEN", "GTE", "95")));
        // GREEN indicator: 客单价 38 with GREEN threshold GTE 25
        mockIndicator("AVG_TICKET_PRICE", "ind-ticket", "客单价", "RESTAURANT", "元",
                new BigDecimal("38.00"),
                List.of(buildThreshold("GREEN", "GTE", "25")));
        // GREEN indicator: 翻台率 2.0 with GREEN threshold GTE 1.2
        mockIndicator("TABLE_TURNOVER", "ind-turnover", "翻台率", "RESTAURANT", "次",
                new BigDecimal("2.00"),
                List.of(buildThreshold("GREEN", "GTE", "1.2")));

        Map<String, Object> result = invoke(Map.of(
                "indicator_codes",
                List.of("FACTORY_YIELD_RATE", "AVG_TICKET_PRICE", "TABLE_TURNOVER")));
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(3, data.get("found"));
        assertEquals(3, data.get("requested"));
        assertEquals("FACTORY_YIELD_RATE", data.get("worstIndicator"));
        assertEquals("RED/ALERT", data.get("worstSeverity"));
        assertTrue(((String) result.get("message")).contains("FACTORY_YIELD_RATE"));
    }

    @Test
    @DisplayName("UT-ICT-03: 部分 indicators 不存在 → notFound 字段含遗漏")
    @SuppressWarnings("unchecked")
    void partialNotFound() throws Exception {
        mockIndicator("AVG_TICKET_PRICE", "ind-ticket", "客单价", "RESTAURANT", "元",
                new BigDecimal("35.00"),
                List.of(buildThreshold("GREEN", "GTE", "25")));
        lenient().when(indicatorRepository.findByCodeAndFactoryIdAndDeletedAtIsNull(
                "NONEXISTENT", FACTORY_ID)).thenReturn(Optional.empty());

        Map<String, Object> result = invoke(Map.of(
                "indicator_codes", List.of("AVG_TICKET_PRICE", "NONEXISTENT")));
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(1, data.get("found"));
        assertEquals(2, data.get("requested"));
        List<String> notFound = (List<String>) data.get("notFound");
        assertEquals(List.of("NONEXISTENT"), notFound);
        assertTrue(((String) result.get("message")).contains("未找到: NONEXISTENT"));
    }

    @Test
    @DisplayName("UT-ICT-04: empty list → VALIDATION_ERROR")
    @SuppressWarnings("unchecked")
    void emptyList() throws Exception {
        Map<String, Object> result = invoke(Map.of("indicator_codes", List.of()));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals("VALIDATION_ERROR", data.get("status"));
    }

    @Test
    @DisplayName("UT-ICT-05: 超过 10 个 → VALIDATION_ERROR")
    @SuppressWarnings("unchecked")
    void tooManyIndicators() throws Exception {
        List<String> codes = new java.util.ArrayList<>();
        for (int i = 0; i < 11; i++) codes.add("CODE_" + i);
        Map<String, Object> result = invoke(Map.of("indicator_codes", codes));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals("VALIDATION_ERROR", data.get("status"));
        assertEquals(10, data.get("limit"));
    }

    @Test
    @DisplayName("UT-ICT-06: comma-separated string fallback — LLM 偶尔传 string 而非 array")
    @SuppressWarnings("unchecked")
    void stringFallback() throws Exception {
        mockIndicator("AVG_TICKET_PRICE", "ind-ticket", "客单价", "RESTAURANT", "元",
                new BigDecimal("35.00"), List.of(buildThreshold("GREEN", "GTE", "25")));
        mockIndicator("TABLE_TURNOVER", "ind-turnover", "翻台率", "RESTAURANT", "次",
                new BigDecimal("2.00"), List.of(buildThreshold("GREEN", "GTE", "1.2")));

        Map<String, Object> result = invoke(Map.of(
                "indicator_codes", "AVG_TICKET_PRICE, TABLE_TURNOVER"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(2, data.get("found"));
    }

    @Test
    @DisplayName("UT-ICT-07: 重复 codes 去重")
    @SuppressWarnings("unchecked")
    void deduplication() throws Exception {
        mockIndicator("AVG_TICKET_PRICE", "ind-ticket", "客单价", "RESTAURANT", "元",
                new BigDecimal("35.00"), List.of(buildThreshold("GREEN", "GTE", "25")));

        Map<String, Object> result = invoke(Map.of(
                "indicator_codes",
                List.of("AVG_TICKET_PRICE", "avg_ticket_price", "AVG_TICKET_PRICE")));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals(1, data.get("found"), "去重后只 query 1 次");
        assertEquals(1, data.get("requested"));
    }

    // ============================================================
    // Helpers
    // ============================================================

    private void mockIndicator(String code, String id, String name, String category, String unit,
                               BigDecimal latestValue, List<IndicatorThreshold> thresholds) {
        Indicator i = new Indicator();
        i.setId(id);
        i.setFactoryId(FACTORY_ID);
        i.setCode(code);
        i.setName(name);
        i.setCategory(category);
        i.setUnit(unit);
        i.setIsActive(true);
        lenient().when(indicatorRepository.findByCodeAndFactoryIdAndDeletedAtIsNull(code, FACTORY_ID))
                .thenReturn(Optional.of(i));

        IndicatorVersion v = new IndicatorVersion();
        v.setIndicatorId(id);
        v.setFactoryId(FACTORY_ID);
        v.setValue(latestValue);
        v.setComputedAt(LocalDateTime.now());
        lenient().when(versionRepository.findFirstByIndicatorIdOrderByComputedAtDesc(id))
                .thenReturn(Optional.of(v));

        lenient().when(thresholdRepository.findByIndicatorIdAndIsActiveTrue(id))
                .thenReturn(thresholds);
    }

    private IndicatorThreshold buildThreshold(String level, String operator, String value) {
        IndicatorThreshold t = new IndicatorThreshold();
        t.setAlertLevel(level);
        t.setOperator(operator);
        t.setThresholdValue(new BigDecimal(value));
        t.setIsActive(true);
        return t;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(Map<String, Object> params) throws Exception {
        // Default Mockito @Mock for Optional-returning methods returns Optional.empty(),
        // so unknown codes naturally fall into notFound path without explicit stubbing.
        Method m = IndicatorComparisonTool.class.getDeclaredMethod(
                "doExecute", String.class, Map.class, Map.class);
        m.setAccessible(true);
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", FACTORY_ID);
        ctx.put("userId", 1L);
        try {
            return (Map<String, Object>) m.invoke(tool, FACTORY_ID, params, ctx);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (ite.getCause() instanceof Exception ee) throw ee;
            throw ite;
        }
    }
}
