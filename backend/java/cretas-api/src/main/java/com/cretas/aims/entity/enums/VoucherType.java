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
    COST_CARRYOVER,
    /**
     * 收款凭证 (资金段, finance audit Bug 5) — 客户收款时的现金流动 GL。
     * 借 1002 银行存款 (或 1001 库存现金, 按 paymentMethod) / 贷 1122 应收账款 = 收款金额。
     * 客户辅助核算挂在 1122 贷方行。此前 {@code recordArPayment} 只写 AR 子账 +
     * Customer.currentBalance, 从不过 GL → 1002 是死账 (只被工资 WAGE 贷)、1122 只增不减
     * (仅退货冲减)。本类型让现金入账 + 应收冲减真正进 GL, 使 1002/1122 随真实资金流动。
     */
    CASH_RECEIPT,
    /**
     * 付款凭证 (资金段, finance audit Bug 5) — 供应商付款时的现金流动 GL。
     * 借 2202 应付账款 / 贷 1002 银行存款 (或 1001 库存现金, 按 paymentMethod) = 付款金额。
     * 供应商辅助核算挂在 2202 借方行。此前 {@code recordApPayment} 只写 AP 子账 +
     * Supplier.currentBalance, 从不过 GL → 2202 只增不减 (仅退货冲减)。本类型让应付冲减 +
     * 现金付出真正进 GL, 使 2202/1002 随真实资金流动。
     */
    CASH_PAYMENT
}
