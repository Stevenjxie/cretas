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
import static org.mockito.Mockito.when;

/** Unit tests for {@link IndicatorAlertTool} (Sprint 11 D5). */
@ExtendWith(MockitoExtension.class)
class IndicatorAlertToolTest {

    private static final String FACTORY_ID = "F999_MOCK";

    @InjectMocks
    private IndicatorAlertTool tool;

    @Mock
    private IndicatorRepository indicatorRepository;

    @Mock
    private IndicatorVersionRepository versionRepository;

    @Mock
    private IndicatorThresholdRepository thresholdRepository;

    @Test
    @DisplayName("UT-IAT-01: metadata — READ + LOW + tool_name=indicator_alert + 0 required params")
    void metadata() {
        assertEquals("indicator_alert", tool.getToolName());
        assertEquals(ToolExecutor.ActionType.READ, tool.getActionType());
        assertEquals(ToolExecutor.RiskLevel.LOW, tool.getRiskLevel());
        Map<String, Object> schema = tool.getParametersSchema();
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertTrue(required.isEmpty(), "indicator_alert 应 0 个必需参数 (factory-wide scan)");
    }

    @Test
    @DisplayName("UT-IAT-02: 5 indicators — 2 RED + 1 YELLOW + 2 GREEN, default WARNING 返 3 行 (排除 GREEN)")
    @SuppressWarnings("unchecked")
    void happyPath() throws Exception {
        Indicator iRed1 = indicator("FACTORY_YIELD_RATE", "ind-1", "良品率", "FACTORY");
        Indicator iRed2 = indicator("FOOD_SAFETY_PASS_RATE", "ind-2", "食安通过率", "QUALITY");
        Indicator iYellow = indicator("RAW_WASTAGE_RATE", "ind-3", "食材损耗率", "RESTAURANT");
        Indicator iGreen1 = indicator("AVG_TICKET_PRICE", "ind-4", "客单价", "RESTAURANT");
        Indicator iGreen2 = indicator("TABLE_TURNOVER", "ind-5", "翻台率", "RESTAURANT");

        when(indicatorRepository.findActiveByFactoryId(FACTORY_ID))
                .thenReturn(List.of(iRed1, iRed2, iYellow, iGreen1, iGreen2));

        mockLatest("ind-1", new BigDecimal("88.00"));   // RED: < 90
        mockLatest("ind-2", new BigDecimal("95.00"));   // RED: < 96
        mockLatest("ind-3", new BigDecimal("6.50"));    // YELLOW: >= 6
        mockLatest("ind-4", new BigDecimal("35.00"));   // GREEN
        mockLatest("ind-5", new BigDecimal("2.00"));    // GREEN

        when(thresholdRepository.findByIndicatorIdAndIsActiveTrue("ind-1"))
                .thenReturn(List.of(threshold("RED", "LT", "90")));
        when(thresholdRepository.findByIndicatorIdAndIsActiveTrue("ind-2"))
                .thenReturn(List.of(threshold("RED", "LT", "96")));
        when(thresholdRepository.findByIndicatorIdAndIsActiveTrue("ind-3"))
                .thenReturn(List.of(threshold("YELLOW", "GTE", "6")));
        when(thresholdRepository.findByIndicatorIdAndIsActiveTrue("ind-4"))
                .thenReturn(List.of(threshold("GREEN", "GTE", "25")));
        when(thresholdRepository.findByIndicatorIdAndIsActiveTrue("ind-5"))
                .thenReturn(List.of(threshold("GREEN", "GTE", "1.2")));

        Map<String, Object> result = invoke(Map.of());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(2, data.get("redCount"));
        assertEquals(1, data.get("yellowCount"));
        assertEquals(3, data.get("totalBreachedCount"));
        assertEquals(5, data.get("totalScannedCount"));

        List<Map<String, Object>> breached = (List<Map<String, Object>>) data.get("breached");
        // First 2 should be RED (severity desc)
        assertEquals("RED", breached.get(0).get("breachLevel"));
        assertEquals("RED", breached.get(1).get("breachLevel"));
        assertEquals("YELLOW", breached.get(2).get("breachLevel"));

        assertTrue(((String) result.get("message")).contains("2 个红灯"));
        assertTrue(((String) result.get("message")).contains("1 个黄灯"));
    }

    @Test
    @DisplayName("UT-IAT-03: min_severity=ALERT — 仅返 RED, 跳过 YELLOW")
    @SuppressWarnings("unchecked")
    void minSeverityAlert() throws Exception {
        Indicator iRed = indicator("FACTORY_YIELD_RATE", "ind-1", "良品率", "FACTORY");
        Indicator iYellow = indicator("RAW_WASTAGE_RATE", "ind-2", "食材损耗率", "RESTAURANT");

        when(indicatorRepository.findActiveByFactoryId(FACTORY_ID))
                .thenReturn(List.of(iRed, iYellow));
        mockLatest("ind-1", new BigDecimal("88.00"));
        mockLatest("ind-2", new BigDecimal("6.50"));
        when(thresholdRepository.findByIndicatorIdAndIsActiveTrue("ind-1"))
                .thenReturn(List.of(threshold("RED", "LT", "90")));
        when(thresholdRepository.findByIndicatorIdAndIsActiveTrue("ind-2"))
                .thenReturn(List.of(threshold("YELLOW", "GTE", "6")));

        Map<String, Object> result = invoke(Map.of("min_severity", "ALERT"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(1, data.get("totalBreachedCount"));
        assertEquals(1, data.get("redCount"));
        assertEquals(0, data.get("yellowCount"));
    }

    @Test
    @DisplayName("UT-IAT-04: category=RESTAURANT 过滤 — 只扫餐饮指标")
    @SuppressWarnings("unchecked")
    void categoryFilter() throws Exception {
        Indicator iWastage = indicator("RAW_WASTAGE_RATE", "ind-w", "食材损耗率", "RESTAURANT");

        when(indicatorRepository.findByFactoryIdAndCategoryAndIsActiveTrueAndDeletedAtIsNull(
                FACTORY_ID, "RESTAURANT")).thenReturn(List.of(iWastage));
        mockLatest("ind-w", new BigDecimal("8.50"));
        when(thresholdRepository.findByIndicatorIdAndIsActiveTrue("ind-w"))
                .thenReturn(List.of(threshold("ALERT", ">=", "8")));

        Map<String, Object> result = invoke(Map.of(
                "category", "restaurant",
                "min_severity", "WARNING"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(1, data.get("totalScannedCount"));
        assertEquals("RESTAURANT", data.get("category"));
    }

    @Test
    @DisplayName("UT-IAT-05: 全部正常 → totalBreachedCount=0 + 友好 message")
    @SuppressWarnings("unchecked")
    void allClear() throws Exception {
        Indicator i = indicator("AVG_TICKET_PRICE", "ind-x", "客单价", "RESTAURANT");
        when(indicatorRepository.findActiveByFactoryId(FACTORY_ID)).thenReturn(List.of(i));
        mockLatest("ind-x", new BigDecimal("35.00"));
        when(thresholdRepository.findByIndicatorIdAndIsActiveTrue("ind-x"))
                .thenReturn(List.of(threshold("GREEN", "GTE", "25")));

        Map<String, Object> result = invoke(Map.of());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(0, data.get("totalBreachedCount"));
        assertTrue(((String) result.get("message")).contains("全部正常"));
    }

    @Test
    @DisplayName("UT-IAT-06: invalid min_severity → VALIDATION_ERROR")
    @SuppressWarnings("unchecked")
    void invalidMinSeverity() throws Exception {
        Map<String, Object> result = invoke(Map.of("min_severity", "PINK"));
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        assertEquals("VALIDATION_ERROR", data.get("status"));
        assertEquals("min_severity", data.get("field"));
    }

    @Test
    @DisplayName("UT-IAT-07: 无 latest value (新指标) → skip, 不计入 breached")
    @SuppressWarnings("unchecked")
    void skipsIndicatorsWithoutValue() throws Exception {
        Indicator i = indicator("FACTORY_YIELD_RATE", "ind-1", "良品率", "FACTORY");
        i.setLastValue(null);
        when(indicatorRepository.findActiveByFactoryId(FACTORY_ID)).thenReturn(List.of(i));
        lenient().when(versionRepository.findFirstByIndicatorIdOrderByComputedAtDesc("ind-1"))
                .thenReturn(Optional.empty());
        lenient().when(thresholdRepository.findByIndicatorIdAndIsActiveTrue("ind-1"))
                .thenReturn(List.of(threshold("RED", "LT", "90")));

        Map<String, Object> result = invoke(Map.of());
        Map<String, Object> data = (Map<String, Object>) result.get("data");

        assertEquals(0, data.get("totalBreachedCount"));
        assertEquals(1, data.get("totalScannedCount"));
    }

    // ============================================================
    // Helpers
    // ============================================================

    private Indicator indicator(String code, String id, String name, String category) {
        Indicator i = new Indicator();
        i.setId(id);
        i.setFactoryId(FACTORY_ID);
        i.setCode(code);
        i.setName(name);
        i.setCategory(category);
        i.setUnit("%");
        i.setIsActive(true);
        return i;
    }

    private void mockLatest(String indicatorId, BigDecimal value) {
        IndicatorVersion v = new IndicatorVersion();
        v.setIndicatorId(indicatorId);
        v.setFactoryId(FACTORY_ID);
        v.setValue(value);
        v.setComputedAt(LocalDateTime.now());
        lenient().when(versionRepository.findFirstByIndicatorIdOrderByComputedAtDesc(indicatorId))
                .thenReturn(Optional.of(v));
    }

    private IndicatorThreshold threshold(String level, String op, String val) {
        IndicatorThreshold t = new IndicatorThreshold();
        t.setAlertLevel(level);
        t.setOperator(op);
        t.setThresholdValue(new BigDecimal(val));
        t.setIsActive(true);
        return t;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(Map<String, Object> params) throws Exception {
        Method m = IndicatorAlertTool.class.getDeclaredMethod(
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
