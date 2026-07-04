package com.cretas.aims.dto.finance.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 现金流量表 (Cash Flow Statement) DTO — Sprint 7 T3 报表三表.
 *
 * <p>期间内三类活动 (中国 GAAP):
 * <ul>
 *   <li>经营活动现金流 (Operating CF) — 销售/采购/工资/税费, 现金账户对手方为 5xxx/6xxx (REVENUE/COST/EXPENSE)</li>
 *   <li>投资活动现金流 (Investing CF) — 购置固定资产 / 长期股权, 对手方 14xx/15xx/19xx</li>
 *   <li>筹资活动现金流 (Financing CF) — 借款 / 增资 / 分红, 对手方 2xxx (long-term debt) / 4xxx (equity)</li>
 * </ul>
 *
 * <p>算法 (直接法):
 * 1. 找出所有"现金类"账户 (code IN ('1001','1002','1012')) 在期间内的 voucher entries
 * 2. 对每条 entry, 找同 voucher 的对手 entry (counterpart) — code 前缀决定活动类别
 * 3. 现金账户的 debit 是流入, credit 是流出
 *
 * <p>简化处理: 无法精确匹配的归入"经营活动" (default).
 *
 * @since 2026-05-20 Sprint 7 T3
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CashFlowDTO {

    private String factoryId;
    private Integer startYear;
    private Integer startMonth;
    private Integer endYear;
    private Integer endMonth;

    /** 经营活动现金流明细. */
    private List<Activity> operatingActivities;

    /** 投资活动现金流明细. */
    private List<Activity> investingActivities;

    /** 筹资活动现金流明细. */
    private List<Activity> financingActivities;

    /** 经营活动净现金流 (流入 - 流出). */
    private BigDecimal operatingNetCashFlow;

    /** 投资活动净现金流. */
    private BigDecimal investingNetCashFlow;

    /** 筹资活动净现金流. */
    private BigDecimal financingNetCashFlow;

    /** 现金净增加额 = 经营 + 投资 + 筹资. */
    private BigDecimal netIncreaseInCash;

    /** 期初现金余额 (期间开始前一日的现金账户余额). */
    private BigDecimal beginningCash;

    /** 期末现金余额 (期间最后一日的现金账户余额). */
    private BigDecimal endingCash;

    /** 报表生成时间戳. */
    private String generatedAt;

    /**
     * F006 财务审计 Bug 6 follow-up (2026-07-04): 期间内待过账 (DRAFT, 未财审) 凭证数. 报表口径
     * 已切到 POSTED-only (人工审核制: DRAFT 不计入官方报表), 本字段让财务人员看到"为什么数字比
     * 预期少" — 而不是悄悄少算却不说明. 0 = 无待办. 与 BalanceSheetDTO/IncomeStatementDTO 同名
     * 字段同一原则.
     */
    private Long pendingDraftVoucherCount;

    /**
     * 单笔现金活动 — 对手科目 + 流入流出.
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Activity {
        /** 对手科目 code (e.g. "5001"). */
        private String counterpartCode;
        /** 对手科目 name (e.g. "主营业务收入"). */
        private String counterpartName;
        /** 现金流入 (正数). */
        private BigDecimal inflow;
        /** 现金流出 (正数). */
        private BigDecimal outflow;
        /** 净额 = inflow - outflow. */
        private BigDecimal netAmount;
    }
}
