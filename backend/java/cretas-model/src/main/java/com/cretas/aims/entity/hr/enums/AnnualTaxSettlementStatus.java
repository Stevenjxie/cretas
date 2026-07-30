package com.cretas.aims.entity.hr.enums;

/**
 * 年度汇算状态 (#833 follow-up).
 *
 * <ul>
 *   <li>{@link #DRAFT} — 草稿, 可任意重算 / 修改 / 删除</li>
 *   <li>{@link #CONFIRMED} — HR 已确认, 但尚未申报税局, 仍可解锁回 DRAFT 重算</li>
 *   <li>{@link #REPORTED} — 已申报税局, R5 防呆锁住, 不可改 / 不可删</li>
 * </ul>
 *
 * @since 2026-05-18
 */
public enum AnnualTaxSettlementStatus {
    DRAFT,
    CONFIRMED,
    REPORTED
}
