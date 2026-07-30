package com.cretas.aims.entity.canvas;

/**
 * Canvas-Thresholds 值类型 (Phase A P0-1).
 *
 * <p>所有 threshold 都以 String 存储在 {@code threshold_value} 列, 通过此类型决定如何
 * 解析为运行时 Java 类型:
 * <ul>
 *   <li>{@link #INTEGER} — 整数 (e.g. MAX_DEPTH=10, AGING_FRESH=30)</li>
 *   <li>{@link #DECIMAL} — {@code BigDecimal} (e.g. TURNOVER_RED_THRESHOLD=6, WARNING_RATIO=0.80)</li>
 *   <li>{@link #DOUBLE}  — 双精度浮点 (e.g. COLD_CHAIN_TEMP_MAX=-18.0, FAST_THRESHOLD=0.3)</li>
 *   <li>{@link #STRING}  — 文本 (future use, 当前无 site)</li>
 * </ul>
 *
 * @since Canvas Phase A (2026-05-21)
 */
public enum ThresholdValueType {
    INTEGER,
    DECIMAL,
    DOUBLE,
    STRING
}
