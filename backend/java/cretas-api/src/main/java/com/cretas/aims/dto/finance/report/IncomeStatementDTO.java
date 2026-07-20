package com.cretas.aims.dto.finance.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 利润表 (Income Statement / P&L) DTO — Sprint 7 T3 报表三表.
 *
 * <p>期间内 (start/end month) 累计发生额, 中国 GAAP 简化 4 层:
 * <pre>
 *   营业收入 (REVENUE 科目, 5/6xxx)
 *   减:  营业成本 (COST 科目, 5xxx)
 *   ────────────────────────────
 *   营业毛利 (= revenue - cost)
 *   减:  营业费用 / 管理费用 / 财务费用 (EXPENSE 科目, 66xx)
 *   ────────────────────────────
 *   营业利润 (operating profit)
 *   减:  所得税 (简化: EXPENSE 含"所得税"科目)
 *   ────────────────────────────
 *   净利润 (net profit)
 * </pre>
 *
 * <p>计算逻辑:
 * <ul>
 *   <li>REVENUE (CREDIT_NORMAL): SUM(credit) - SUM(debit)</li>
 *   <li>COST + EXPENSE (DEBIT_NORMAL): SUM(debit) - SUM(credit)</li>
 * </ul>
 * 期间过滤: voucher.voucherDate BETWEEN start AND end (start = first day of startMonth,
 * end = last day of endMonth).
 *
 * @since 2026-05-20 Sprint 7 T3
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class IncomeStatementDTO {

    /** Whether the requested period contains at least one posted COST line. */
    private boolean costDataAvailable;

    /**
     * Truth status for gross-margin percentage. A zero posted cost line is
     * calculable; an entirely absent cost section is not the same as cost=0.
     */
    private GrossMarginStatus grossMarginStatus;

    /** Human-readable explanation for clients and AI tools. */
    private String grossMarginStatusMessage;

    private String factoryId;
    private Integer startYear;
    private Integer startMonth;
    private Integer endYear;
    private Integer endMonth;

    /** 营业收入明细 (REVENUE 科目). */
    private List<BalanceSheetDTO.LineItem> revenues;

    /** 营业成本明细 (COST 科目). */
    private List<BalanceSheetDTO.LineItem> costs;

    /** 营业费用 / 管理费用 / 财务费用 (EXPENSE 科目). */
    private List<BalanceSheetDTO.LineItem> expenses;

    /** 营业收入合计. */
    private BigDecimal totalRevenue;

    /** 营业成本合计. */
    private BigDecimal totalCost;

    /** 营业毛利 = totalRevenue - totalCost. */
    private BigDecimal grossProfit;

    /** 营业费用合计. */
    private BigDecimal totalExpense;

    /** 营业利润 = grossProfit - totalExpense. */
    private BigDecimal operatingProfit;

    /** 所得税额 (从 expenses 中识别"所得税"科目, 默认 0). */
    private BigDecimal incomeTax;

    /** 净利润 = operatingProfit - incomeTax. */
    private BigDecimal netProfit;

    /** 报表生成时间戳. */
    private String generatedAt;

    /**
     * F006 财务审计 Bug 6 (2026-07-04): 期间内待过账 (DRAFT, 未财审) 凭证数. 报表口径已切到
     * POSTED-only, 本字段让财务人员看到"为什么数字比预期少". 0 = 无待办.
     */
    private Long pendingDraftVoucherCount;

    public enum GrossMarginStatus {
        CALCULABLE,
        MISSING_COST_DATA,
        NO_REVENUE
    }
}
