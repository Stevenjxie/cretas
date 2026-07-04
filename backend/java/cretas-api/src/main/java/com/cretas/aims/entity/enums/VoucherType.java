package com.cretas.aims.entity.enums;

/**
 * 凭证类型 — 业务单 → 凭证 generator 映射的 7 类.
 * 参考宏见 ERP Round 5 deep-audit 实测.
 */
public enum VoucherType {
    /** 销售收款 — SalesOrder confirmed → 应收/收入 借贷 */
    SALES_RECEIPT,
    /** 采购付款 — PurchaseOrder approved → 库存/应付 借贷 */
    PURCHASE_PAYMENT,
    /** 库存调拨 — InternalTransfer → 仓库间科目调整 */
    INVENTORY_TRANSFER,
    /** 报销 — ExpenseClaim → 费用/现金 借贷 */
    EXPENSE,
    /** 工资发放 — PayrollRecord paid → 应付职工薪酬/现金 借贷 */
    WAGE,
    /** 退货 — ReturnOrder → 反向销售凭证 */
    RETURN,
    /** 折旧 — DepreciationSchedule → 累计折旧/管理费用 */
    DEPRECATION,
    /** 结转损益凭证 (期末自动). */
    PL_CLOSING,
    /** 库存盘点差异 — 半成品/仓库盘点生效: 盘盈=收入(6301)/盘亏=损耗(6602.01), 库存(1405)增减. */
    INVENTORY_STOCKTAKE,
    /**
     * 结转成本凭证 (期末自动) — 分阶段期末权责化销售成本 (phased period-end COGS)。
     * 借 6401 主营业务成本 / 贷 1405 库存商品 = 期内已发货成品 (发货数量 × 批次单位成本)。
     * 让 6401 有真实发生额, 经 {@code PL_CLOSING} 结转进 4103, 修复"毛利=收入"缺口;
     * 同时释放 1405, 使其反映真实剩余存货 (采购已借 1405, COGS 结转贷 1405)。
     */
    COST_CARRYOVER
}
