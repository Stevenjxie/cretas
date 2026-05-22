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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IndicatorQueryTool} (Sprint 11 D3).
 */
@ExtendWith(MockitoExtension.class)
class IndicatorQueryToolTest {

    private static final String FACTORY_ID = "F999_MOCK";
    private static final String INDICATOR_ID = "ind-uuid-yield-rate";
    private static final String CODE = "FACTORY_YIELD_RATE";

    @InjectMocks
    private IndicatorQueryTool tool;

    @Mock
    private IndicatorRepository indicatorRepository;

    @Mock
    private IndicatorVersionRepository versionRepository;

    @Mock
    private IndicatorThresholdRepository thresholdRepository;

    // ============================================================
    // Metadata
    // ============================================================

    @Test
    @DisplayName("UT-IQT-01: tool 元数据 — name=indicator_query, READ, LOW")
    void metadata() {
        assertEquals("indicator_query", tool.getToolName());
        assertEquals(ToolExecutor.ActionType.READ, tool.getActionType());
        assertEquals(ToolExecutor.RiskLevel.LOW, tool.getRiskLevel());
        assertNotNull(tool.getDescription());
        Map<String, Object> schema = tool.getParametersSchema();
        assertEquals("object", schema.get("type"));
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertEquals(List.of("indicator_code"), required);
    }

    // ============================================================
    // Happy path
    // ============================================================

    @Test
    @DisplayName("UT-IQT-02: happy path — 良品率 96.5, threshold GTE 95 → GREEN")
    @SuppressWarnings("unchecked")
    void happyPathGreen() throws Exception {
        Indicator i = buildIndicator(CODE, "良品率", "FACTORY", "%");
        when(indicatorRepository.findByCodeAndFactoryIdAndDeletedAtIsNull(CODE, FACTORY_ID))
                .thenReturn(Optional.of(i));

        IndicatorVersion latest = buildVersion(new BigDecimal("96.50"), LocalDate.now());
        when(versionRepository.findFirstByIndicatorIdOrderByComputedAtDesc(INDICATOR_ID))
                .thenReturn(Optional.of(latest));
        when(versionRepository.findInWindow(eq(INDICATOR_ID), any(), any()))
                .thenReturn(List.of(
                        buildVersion(new BigDecimal("95.10"), LocalDate.now().minusDays(2)),
                        buildVersion(new BigDecimal("96.20"), LocalDate.now().minusDays(1)),
                        latest));

        IndicatorThreshold green = buildThreshold("GREEN", "GTE", new BigDecimal("95"));
        IndicatorThreshold yellow = buildThreshold("YELLOW", "GTE", new BigDecimal("93"));
        IndicatorThreshold red = buildThreshold("RED", "LT", new BigDecimal("90"));
        when(thresholdRepository.findByIndicatorIdAndIsActiveTrue(INDICATOR_ID))
                .thenReturn(List.of(green, yellow, red));

        Map<String, Object> result = invoke(FACTORY_ID, Map.of("indicator_code", CODE));
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals("GREEN", data.get("breachLevel"));
        assertEquals(new BigDecimal("96.50"), data.get("currentValue"));
        assertEquals(3, data.get("trendSize"));
        Map<String, Object> indicatorMap = (Map<String, Object>) data.get("indicator");
        assertEquals(CODE, indicatorMap.get("code"));
        assertEquals("良品率", indicatorMap.get("name"));
        assertTrue(((String) result.get("message")).contains("良品率"));
    }

    @Test
    @DisplayName("UT-IQT-03: 良品率 88 触 RED (LT 90 命中) — mock 故障日场景")
    @SuppressWarnings("unchecked")
    void breachRed() throws Exception {
        Indicator i = buildIndicator(CODE, "良品率", "FACTORY", "%");
        when(indicatorRepository.findByCodeAndFactoryIdAndDeletedAtIsNull(CODE, FACTORY_ID))
                .thenReturn(Optional.of(i));

        IndicatorVersion latest = buildVersion(new BigDecimal("88.00"), LocalDate.now());
        when(versionRepository.findFirstByIndicatorIdOrderByComputedAtDesc(INDICATOR_ID))
                .thenReturn(Optional.of(latest));
        when(versionRepository.findInWindow(eq(INDICATOR_ID), any(), any()))
                .thenReturn(List.of(latest));

        when(thresholdRepository.findByIndicatorIdAndIsActiveTrue(INDICATOR_ID))
                .thenReturn(List.of(
                        buildThreshold("GREEN", "GTE", new BigDecimal("95")),
                        buildThreshold("YELLOW", "GTE", new BigDecimal("93")),
                        buildThreshold("RED", "LT", new BigDecimal("90"))));

        Map<String, Object> result = invoke(FACTORY_ID, Map.of("indicator_code", CODE));
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals("RED", data.get("breachLevel"));
    }

    @Test
    @DisplayName("UT-IQT-04: mock convention operator (>= / <=) — WARNING/ALERT level 支持")
    @SuppressWarnings("unchecked")
    void breachMockConvention() throws Exception {
        Indicator i = buildIndicator("RAW_WASTAGE_RATE", "食材损耗率", "RESTAURANT", "%");
        when(indicatorRepository.findByCodeAndFactoryIdAndDeletedAtIsNull(
                "RAW_WASTAGE_RATE", FACTORY_ID)).thenReturn(Optional.of(i));

        IndicatorVersion latest = buildVersion(new BigDecimal("8.50"), LocalDate.now());
        when(versionRepository.findFirstByIndicatorIdOrderByComputedAtDesc(INDICATOR_ID))
                .thenReturn(Optional.of(latest));
        when(versionRepository.findInWindow(eq(INDICATOR_ID), any(), any()))
                .thenReturn(List.of(latest));

        // Mock generator wrote: WARNING/ALERT level + >=/<= operator
        when(thresholdRepository.findByIndicatorIdAndIsActiveTrue(INDICATOR_ID))
                .thenReturn(List.of(
                        buildThreshold("WARNING", ">=", new BigDecimal("6")),
                        buildThreshold("ALERT", ">=", new BigDecimal("8"))));

        Map<String, Object> result = invoke(FACTORY_ID, Map.of("indicator_code", "raw_wastage_rate"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals("ALERT", data.get("breachLevel"),
                "8.50 应命中 ALERT (>= 8), 不应回退到 WARNING");
    }

    // ============================================================
    // Edge cases
    // ============================================================

    @Test
    @DisplayName("UT-IQT-05: indicator 不存在 → NOT_FOUND status")
    @SuppressWarnings("unchecked")
    void notFound() throws Exception {
        when(indicatorRepository.findByCodeAndFactoryIdAndDeletedAtIsNull("NOPE", FACTORY_ID))
                .thenReturn(Optional.empty());

        Map<String, Object> result = invoke(FACTORY_ID, Map.of("indicator_code", "NOPE"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals("NOT_FOUND", data.get("status"));
        assertEquals("NOPE", data.get("indicatorCode"));
        assertTrue(((String) result.get("message")).contains("不存在"));
    }

    @Test
    @DisplayName("UT-IQT-06: 没有 latest version (新建指标) — currentValue 回退到 indicator.lastValue (null OK)")
    @SuppressWarnings("unchecked")
    void emptyVersions() throws Exception {
        Indicator i = buildIndicator(CODE, "良品率", "FACTORY", "%");
        i.setLastValue(null);
        when(indicatorRepository.findByCodeAndFactoryIdAndDeletedAtIsNull(CODE, FACTORY_ID))
                .thenReturn(Optional.of(i));
        when(versionRepository.findFirstByIndicatorIdOrderByComputedAtDesc(INDICATOR_ID))
                .thenReturn(Optional.empty());
        when(versionRepository.findInWindow(eq(INDICATOR_ID), any(), any()))
                .thenReturn(List.of());
        when(thresholdRepository.findByIndicatorIdAndIsActiveTrue(INDICATOR_ID))
                .thenReturn(List.of());

        Map<String, Object> result = invoke(FACTORY_ID, Map.of("indicator_code", CODE));
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertNull(data.get("currentValue"));
        assertNull(data.get("breachLevel"));
        assertEquals(0, data.get("trendSize"));
        assertTrue(((String) result.get("message")).contains("暂无数据"));
    }

    @Test
    @DisplayName("UT-IQT-07: period_start > period_end auto-swapped + lowercase code normalized")
    @SuppressWarnings("unchecked")
    void normalizesInputs() throws Exception {
        Indicator i = buildIndicator(CODE, "良品率", "FACTORY", "%");
        when(indicatorRepository.findByCodeAndFactoryIdAndDeletedAtIsNull(CODE, FACTORY_ID))
                .thenReturn(Optional.of(i));
        when(versionRepository.findFirstByIndicatorIdOrderByComputedAtDesc(INDICATOR_ID))
                .thenReturn(Optional.empty());
        when(versionRepository.findInWindow(eq(INDICATOR_ID), any(), any()))
                .thenReturn(List.of());
        when(thresholdRepository.findByIndicatorIdAndIsActiveTrue(INDICATOR_ID))
                .thenReturn(List.of());

        // lowercase code + flipped dates
        Map<String, Object> params = new HashMap<>();
        params.put("indicator_code", "factory_yield_rate");
        params.put("period_start", "2026-05-22");
        params.put("period_end", "2026-04-22");

        Map<String, Object> result = invoke(FACTORY_ID, params);
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        LocalDate ps = (LocalDate) data.get("periodStart");
        LocalDate pe = (LocalDate) data.get("periodEnd");
        assertTrue(ps.isBefore(pe) || ps.isEqual(pe),
                "period_start should be auto-swapped to be before period_end");
    }

    // ============================================================
    // Helper-method unit tests
    // ============================================================

    @Test
    @DisplayName("UT-IQT-08: evaluateBreachLevel — RED 优先于 YELLOW (severity ordering)")
    void severityOrdering() {
        List<IndicatorThreshold> thresholds = List.of(
                buildThreshold("YELLOW", "LT", new BigDecimal("95")),
                buildThreshold("RED", "LT", new BigDecimal("90")));
        // value 85 命中 both YELLOW (LT 95) and RED (LT 90) → RED wins
        assertEquals("RED", tool.evaluateBreachLevel(new BigDecimal("85"), thresholds));
    }

    @Test
    @DisplayName("UT-IQT-09: evaluateBreachLevel — BETWEEN 阈值含上下限")
    void betweenOperator() {
        IndicatorThreshold t = new IndicatorThreshold();
        t.setAlertLevel("GREEN");
        t.setOperator("BETWEEN");
        t.setThresholdValue(new BigDecimal("30"));
        t.setThresholdValueUpper(new BigDecimal("40"));
        t.setIsActive(true);
        assertEquals("GREEN", tool.evaluateBreachLevel(new BigDecimal("35"), List.of(t)));
        assertEquals("GREEN", tool.evaluateBreachLevel(new BigDecimal("30"), List.of(t)));
        assertEquals("GREEN", tool.evaluateBreachLevel(new BigDecimal("40"), List.of(t)));
        // 50 不命中 → 默认 GREEN (因为无任何 threshold 触发)
        assertEquals("GREEN", tool.evaluateBreachLevel(new BigDecimal("50"), List.of(t)));
    }

    @Test
    @DisplayName("UT-IQT-10: downsample helper — 100 行 → 30 行均匀采样")
    void downsampleEvenly() {
        List<Integer> input = new ArrayList<>();
        for (int i = 0; i < 100; i++) input.add(i);
        List<Integer> out = IndicatorQueryTool.downsample(input, 30);
        assertEquals(30, out.size());
        assertEquals(Integer.valueOf(0), out.get(0));
        assertEquals(Integer.valueOf(99), out.get(29));
    }

    @Test
    @DisplayName("UT-IQT-11: downsample helper — N <= maxRows 返回原集合")
    void downsampleNoOp() {
        List<Integer> input = List.of(1, 2, 3);
        assertSame(input, IndicatorQueryTool.downsample(input, 30));
    }

    // ============================================================
    // Helpers
    // ============================================================

    private Indicator buildIndicator(String code, String name, String category, String unit) {
        Indicator i = new Indicator();
        i.setId(INDICATOR_ID);
        i.setFactoryId(FACTORY_ID);
        i.setCode(code);
        i.setName(name);
        i.setCategory(category);
        i.setUnit(unit);
        i.setIsActive(true);
        i.setComputeStrategy("PRECOMPUTED");
        return i;
    }

    private IndicatorVersion buildVersion(BigDecimal value, LocalDate periodDate) {
        IndicatorVersion v = new IndicatorVersion();
        v.setIndicatorId(INDICATOR_ID);
        v.setFactoryId(FACTORY_ID);
        v.setValue(value);
        v.setPeriodStart(periodDate);
        v.setPeriodEnd(periodDate);
        v.setComputedAt(LocalDateTime.now());
        return v;
    }

    private IndicatorThreshold buildThreshold(String level, String operator, BigDecimal value) {
        IndicatorThreshold t = new IndicatorThreshold();
        t.setIndicatorId(INDICATOR_ID);
        t.setFactoryId(FACTORY_ID);
        t.setAlertLevel(level);
        t.setOperator(operator);
        t.setThresholdValue(value);
        t.setIsActive(true);
        return t;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(String factoryId, Map<String, Object> params) throws Exception {
        Method m = IndicatorQueryTool.class.getDeclaredMethod(
                "doExecute", String.class, Map.class, Map.class);
        m.setAccessible(true);
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", factoryId);
        ctx.put("userId", 1L);
        try {
            return (Map<String, Object>) m.invoke(tool, factoryId, params, ctx);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (ite.getCause() instanceof Exception ee) throw ee;
            throw ite;
        }
    }
}
