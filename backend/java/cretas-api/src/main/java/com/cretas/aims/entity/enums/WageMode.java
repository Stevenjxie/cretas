package com.cretas.aims.entity.enums;

/**
 * Sprint 6 Track W4-B: 工资计算 mode.
 *
 * <ul>
 *   <li>{@link #PIECE_RATE}: 计件工资 (默认; PR #57 E ship). 按 PieceRateRule 阶梯 × 件数.</li>
 *   <li>{@link #HOURLY}: 按时工资. 工时 × 时薪 (+ 加班 × 加班倍率).</li>
 *   <li>{@link #MIXED}: 混合工资. HOURLY 全额 + PIECE_RATE 全额 (per spec §W4-B 简化求和).</li>
 * </ul>
 *
 * <p>默认 PIECE_RATE 保证 PR #57 E 现有 flow 向后兼容.
 *
 * @since 2026-05-19
 */
public enum WageMode {
    PIECE_RATE,
    HOURLY,
    MIXED
}
