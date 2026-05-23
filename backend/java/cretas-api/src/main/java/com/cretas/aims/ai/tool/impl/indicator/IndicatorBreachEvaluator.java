package com.cretas.aims.ai.tool.impl.indicator;

import com.cretas.aims.entity.indicator.IndicatorThreshold;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 指标阈值命中评估器 — 共用工具.
 *
 * <p>D3 IndicatorQueryTool + D4 IndicatorComparisonTool 都需要"当前值 V 是否命中阈值
 * + 命中什么级别"的逻辑, 抽到这里集中维护. (D3 IndicatorQueryTool 内部仍保留私有副本 —
 * 为避免并发 session 冲突而做的 belt-and-suspenders; 新代码请使用本工具类.)
 *
 * <p>支持两种 operator 写法:
 * <ul>
 *   <li>Entity-canonical (Phase 1 prod): GT / GTE / LT / LTE / EQ / BETWEEN</li>
 *   <li>Mock convention (Sprint 11 D2): &gt; / &gt;= / &lt; / &lt;= / =</li>
 * </ul>
 *
 * <p>支持两种 alert_level 命名:
 * <ul>
 *   <li>Entity-canonical: GREEN / YELLOW / RED</li>
 *   <li>Mock convention: OK / WARNING / ALERT</li>
 * </ul>
 *
 * @since 2026-05-22 (Sprint 11 D4)
 */
public final class IndicatorBreachEvaluator {

    private IndicatorBreachEvaluator() { /* util class */ }

    /**
     * 评估 value 命中哪个最高 severity 的 threshold.
     *
     * @return 命中级别 (如 RED / YELLOW / ALERT / WARNING); 无 thresholds 返 null; 无命中返 GREEN
     */
    public static String evaluate(BigDecimal value, List<IndicatorThreshold> thresholds) {
        if (value == null || thresholds == null || thresholds.isEmpty()) {
            return null;
        }
        List<IndicatorThreshold> sorted = new ArrayList<>(thresholds);
        sorted.sort((a, b) -> Integer.compare(
                severityRank(b.getAlertLevel()),
                severityRank(a.getAlertLevel())));
        for (IndicatorThreshold t : sorted) {
            if (matches(value, t)) {
                return t.getAlertLevel();
            }
        }
        return "GREEN";
    }

    public static int severityRank(String level) {
        if (level == null) return 0;
        return switch (level.toUpperCase()) {
            case "RED", "ALERT" -> 3;
            case "YELLOW", "WARNING" -> 2;
            case "GREEN", "OK" -> 1;
            default -> 0;
        };
    }

    public static boolean matches(BigDecimal value, IndicatorThreshold t) {
        BigDecimal threshold = t.getThresholdValue();
        if (threshold == null) return false;
        String op = t.getOperator() == null ? "" : t.getOperator().trim().toUpperCase();
        int cmp = value.compareTo(threshold);
        return switch (op) {
            case "GT", ">" -> cmp > 0;
            case "GTE", ">=", "≥" -> cmp >= 0;
            case "LT", "<" -> cmp < 0;
            case "LTE", "<=", "≤" -> cmp <= 0;
            case "EQ", "=", "==" -> cmp == 0;
            case "BETWEEN" -> {
                BigDecimal upper = t.getThresholdValueUpper();
                if (upper == null) yield false;
                yield cmp >= 0 && value.compareTo(upper) <= 0;
            }
            default -> false;
        };
    }
}
