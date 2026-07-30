package com.cretas.aims.entity.enums;

/**
 * 会计科目大类 (6 类, 中国 GAAP 一级分类 + 期间).
 *
 * <p>用于 Sprint 7 T3 报表三表分组:
 * <ul>
 *   <li>{@link #ASSET} (1xxx) → 资产负债表 资产部分</li>
 *   <li>{@link #LIABILITY} (2xxx) → 资产负债表 负债部分</li>
 *   <li>{@link #EQUITY} (4xxx) → 资产负债表 所有者权益部分</li>
 *   <li>{@link #REVENUE} (5/6xxx) → 利润表 营业收入</li>
 *   <li>{@link #EXPENSE} (5/6xxx) → 利润表 营业成本 / 期间费用</li>
 *   <li>{@link #COST} (5xxx) → 利润表 主营业务成本 (区分 EXPENSE 期间费用)</li>
 * </ul>
 */
public enum AccountCategory {
    ASSET,
    LIABILITY,
    EQUITY,
    REVENUE,
    EXPENSE,
    COST
}
