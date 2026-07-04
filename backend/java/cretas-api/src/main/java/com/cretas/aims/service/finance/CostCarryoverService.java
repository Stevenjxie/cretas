package com.cretas.aims.service.finance;

import com.cretas.aims.dto.finance.CostCarryoverSummary;

/**
 * 结转成本 (期末销售成本权责化, phased period-end COGS)。
 *
 * <p>把子账 (发货明细 × 成品批次单位成本) 的期内已发货金额, 在期末锁定时过一张
 * <b>借 6401 主营业务成本 / 贷 1405 库存商品</b> 的凭证 ({@code VoucherType.COST_CARRYOVER}),
 * 使 GL 的存货/成本科目对齐物理事实:
 * <ul>
 *   <li>6401 有真实发生额 → 经 {@link ProfitLossClosingService#closePeriod} 结转进 4103,
 *       修复"毛利=收入 (营业成本恒 0)"缺口。<b>故本方法必须在 closePeriod 聚合 6xxx 之前调用</b>。</li>
 *   <li>释放 1405 (采购已借 1405, 售出结转贷 1405) → 1405 = 采购 − 销售成本 = 真实剩余存货。</li>
 * </ul>
 *
 * <p><b>为何不做生产成本结转 (借 5001/贷 1403 + 借 1405/贷 5001)</b>:
 * 现 GL 里采购直接借 1405 库存商品 (见 {@code PurchasePaymentVoucherGenerator} DEFAULT_DEBIT_CODE),
 * 而非 1403 原材料; 原料→成品的生产移动是 1405 内部转移, 无净 GL 事件, 只有"售出"才是
 * GL 相关的存货事件 (本 COGS 结转已处理)。若再过一张借 1405 的生产完工凭证会<b>把存货双计</b>;
 * 且成品 unitCost 是标量 (原料+调料+人工混合, 无桶明细), 无法把 5001 结平, 会给 5xxx 留下
 * 幽灵余额从而<b>污染 BalanceSheetService</b> (它假设 5xxx 恒 0, 见结转损益 spec §3/§10)。
 * 完整永续存货核算 (5xxx 资产列示 + BalanceSheet 改造) 是独立后续阶段。
 */
public interface CostCarryoverService {

    /**
     * 期末结转本期销售成本 (借 6401/贷 1405)。幂等由调用方 (forceLockAndClose) 的期间
     * {@code closingPostedAt} 守卫 — 本方法只在该守卫内被调一次; reopen 清守卫后重结。
     *
     * <p>诚实 null: 已发货但成品批次无 unitCost 的行不结转, 记 WARN + 在返回小结暴露笔数。
     *
     * @return 结转小结 (含 totalCogs + 未结转笔数)
     */
    CostCarryoverSummary carryCost(String factoryId, int year, int month, Long userId);

    /**
     * 反结账: 红冲该期 active 结转成本凭证 (原凭证置 REVERSED + 借贷互换镜像)。
     * 调用方须先把期间置 OPEN (voidVoucher → assertPeriodOpen)。无 active → no-op。
     */
    void reverseCostCarryover(String factoryId, int year, int month, Long userId);
}
