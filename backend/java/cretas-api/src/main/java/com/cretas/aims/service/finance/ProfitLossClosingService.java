package com.cretas.aims.service.finance;

/**
 * 结转损益自动凭证 (P1: 月末损益类 6xxx → 本年利润 4103)。
 * 方案A: 由 finalize scheduler 在期间 LOCKED 时调 closePeriod; 反结账调 reversePeriodClosing。
 */
public interface ProfitLossClosingService {

    /**
     * 月末结转: 把 [月初,月末] POSTED 的损益类 6xxx 净额结平到 4103 本年利润。
     * 5xxx (生产成本/制造费用) 是存货资本化科目, 不结转。无损益发生额则 no-op。
     * 直建 POSTED 凭证 (绕期间 gate)。created_by=userId (系统触发传 null)。
     */
    void closePeriod(String factoryId, int year, int month, Long userId);

    /**
     * 反结账红冲: 把该期 active 的 PL_CLOSING 凭证红冲 (复用现有红冲)。无 active → no-op。
     */
    void reversePeriodClosing(String factoryId, int year, int month, Long userId);
}
