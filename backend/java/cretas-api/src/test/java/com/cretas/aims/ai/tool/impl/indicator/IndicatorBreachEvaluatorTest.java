package com.cretas.aims.ai.tool.impl.indicator;

import com.cretas.aims.entity.indicator.IndicatorThreshold;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link IndicatorBreachEvaluator} (Sprint 11 D4 — extracted helper). */
class IndicatorBreachEvaluatorTest {

    @Test
    @DisplayName("UT-IBE-01: null/empty inputs → null")
    void nullSafety() {
        assertNull(IndicatorBreachEvaluator.evaluate(null, List.of()));
        assertNull(IndicatorBreachEvaluator.evaluate(new BigDecimal("50"), null));
        assertNull(IndicatorBreachEvaluator.evaluate(new BigDecimal("50"), List.of()));
    }

    @Test
    @DisplayName("UT-IBE-02: severity ordering — RED 优先于 YELLOW")
    void severityOrdering() {
        List<IndicatorThreshold> thresholds = List.of(
                threshold("YELLOW", "LT", "95"),
                threshold("RED", "LT", "90"));
        // 85 命中 both YELLOW (LT 95) 和 RED (LT 90), RED 胜出
        assertEquals("RED", IndicatorBreachEvaluator.evaluate(new BigDecimal("85"), thresholds));
        // 92 仅命中 YELLOW
        assertEquals("YELLOW", IndicatorBreachEvaluator.evaluate(new BigDecimal("92"), thresholds));
        // 100 都不命中 → GREEN
        assertEquals("GREEN", IndicatorBreachEvaluator.evaluate(new BigDecimal("100"), thresholds));
    }

    @Test
    @DisplayName("UT-IBE-03: mock convention 等价 — ALERT vs RED")
    void mockConventionEquivalence() {
        List<IndicatorThreshold> entityCanonical = List.of(
                threshold("YELLOW", "GTE", "6"),
                threshold("RED", "GTE", "8"));
        List<IndicatorThreshold> mockConvention = List.of(
                threshold("WARNING", ">=", "6"),
                threshold("ALERT", ">=", "8"));
        // 8.5 命中最严级别 — 两种命名应分别返回各自最严级别
        assertEquals("RED", IndicatorBreachEvaluator.evaluate(new BigDecimal("8.5"), entityCanonical));
        assertEquals("ALERT", IndicatorBreachEvaluator.evaluate(new BigDecimal("8.5"), mockConvention));
    }

    @Test
    @DisplayName("UT-IBE-04: BETWEEN operator — 含上下限")
    void betweenOperator() {
        IndicatorThreshold t = threshold("GREEN", "BETWEEN", "30");
        t.setThresholdValueUpper(new BigDecimal("40"));
        assertEquals("GREEN", IndicatorBreachEvaluator.evaluate(new BigDecimal("30"), List.of(t)));
        assertEquals("GREEN", IndicatorBreachEvaluator.evaluate(new BigDecimal("35"), List.of(t)));
        assertEquals("GREEN", IndicatorBreachEvaluator.evaluate(new BigDecimal("40"), List.of(t)));
        // 50 不命中 — 但因没有其它 threshold 触发, 默认仍返 GREEN
        assertEquals("GREEN", IndicatorBreachEvaluator.evaluate(new BigDecimal("50"), List.of(t)));
    }

    @Test
    @DisplayName("UT-IBE-05: BETWEEN 缺上限 → 不命中")
    void betweenMissingUpper() {
        IndicatorThreshold t = threshold("YELLOW", "BETWEEN", "30");
        assertEquals("GREEN", IndicatorBreachEvaluator.evaluate(new BigDecimal("35"), List.of(t)));
    }

    @Test
    @DisplayName("UT-IBE-06: 全部 operator 覆盖 — GT/GTE/LT/LTE/EQ + mock 符号")
    void allOperators() {
        assertTrue(matches("11", "GT", "10"));
        assertTrue(matches("11", ">", "10"));
        assertFalse(matches("10", "GT", "10"));

        assertTrue(matches("10", "GTE", "10"));
        assertTrue(matches("10", ">=", "10"));
        assertTrue(matches("10", "≥", "10"));

        assertTrue(matches("9", "LT", "10"));
        assertTrue(matches("9", "<", "10"));
        assertFalse(matches("10", "LT", "10"));

        assertTrue(matches("10", "LTE", "10"));
        assertTrue(matches("10", "<=", "10"));
        assertTrue(matches("10", "≤", "10"));

        assertTrue(matches("10", "EQ", "10"));
        assertTrue(matches("10", "=", "10"));
        assertTrue(matches("10", "==", "10"));
        assertFalse(matches("11", "EQ", "10"));

        assertFalse(matches("10", "WTF", "10"));
    }

    private static boolean matches(String value, String operator, String threshold) {
        IndicatorThreshold t = threshold("TEST", operator, threshold);
        String result = IndicatorBreachEvaluator.evaluate(new BigDecimal(value), List.of(t));
        return result != null && !"GREEN".equals(result);
    }

    private static IndicatorThreshold threshold(String level, String operator, String value) {
        IndicatorThreshold t = new IndicatorThreshold();
        t.setAlertLevel(level);
        t.setOperator(operator);
        t.setThresholdValue(new BigDecimal(value));
        t.setIsActive(true);
        return t;
    }
}
