package com.cretas.aims.dto.finance.report;

import com.cretas.aims.entity.finance.AccountingPeriod;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 资产负债表 (Balance Sheet) DTO — Sprint 7 T3 报表三表.
 *
 * <p>截至期末 (year, month 月底) 余额快照. 中国 GAAP 标准格式:
 * <pre>
 *   资产 (左)              | 负债 + 所有者权益 (右)
 *   ---------------------- | ----------------------
 *   流动资产 (1001-1499)   | 流动负债 (2001-2199)
 *   非流动资产 (15xx-19xx) | 非流动负债 (22xx-25xx)
 *                          | 所有者权益 (4xxx)
 *   ---------------------- | ----------------------
 *   资产总计 (= 负债 + 所有者权益合计 一定平)
 * </pre>
 *
 * <p>计算逻辑 (per Account.balanceType):
 * <ul>
 *   <li>DEBIT_NORMAL (ASSET / COST / EXPENSE): SUM(debit) - SUM(credit)</li>
 *   <li>CREDIT_NORMAL (LIABILITY / EQUITY / REVENUE): SUM(credit) - SUM(debit)</li>
 * </ul>
 * 资产负债表仅纳入 ASSET / LIABILITY / EQUITY 三类科目, 收入/成本/费用归利润表.
 *
 * <p>balanceCheck = (totalAssets == totalLiabilities + totalEquity ± 0.01 容差).
 *
 * @since 2026-05-20 Sprint 7 T3
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BalanceSheetDTO {

    /** 工厂 ID. */
    private String factoryId;

    /** 报表年份 (e.g. 2026). */
    private Integer year;

    /** 报表月份 1-12. */
    private Integer month;

    /** 期间结账状态 — UI 显示 "数据未结账, 可能不准" (Rule 5 dead-end → 跳到 period 页). */
    private AccountingPeriod.Status periodStatus;

    /** 资产部分 (DEBIT_NORMAL ASSET 科目). */
    private List<LineItem> assets;

    /** 负债部分 (CREDIT_NORMAL LIABILITY 科目). */
    private List<LineItem> liabilities;

    /** 所有者权益部分 (CREDIT_NORMAL EQUITY 科目). */
    private List<LineItem> equity;

    /** 资产合计 = sum(assets.amount). */
    private BigDecimal totalAssets;

    /** 负债合计 = sum(liabilities.amount). */
    private BigDecimal totalLiabilities;

    /** 所有者权益合计 = sum(equity.amount). */
    private BigDecimal totalEquity;

    /** 负债 + 所有者权益 合计. */
    private BigDecimal totalLiabilitiesAndEquity;

    /**
     * 平衡校验 — true 当 |totalAssets - totalLiabilitiesAndEquity| <= 0.01.
     * 任何 false 都说明账目有缺陷 (复式失衡 / 数据不全 / 科目分类错误).
     */
    private Boolean balanceCheck;

    /** 报表生成时间戳 (server-side). */
    private String generatedAt;

    /**
     * F006 财务审计 Bug 6 (2026-07-04): 本期 (EPOCH_START ~ 报表截止日) 待过账 (DRAFT, 未财审)
     * 凭证数. 报表口径已切到 POSTED-only (人工审核制: DRAFT 不计入官方报表), 本字段让财务人员
     * 看到"为什么数字比预期少" — 而不是悄悄少算却不说明. 0 = 无待办.
     */
    private Long pendingDraftVoucherCount;

    /**
     * 报表行项 — accountCode/name + amount.
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class LineItem {
        private String accountCode;
        private String accountName;
        private BigDecimal amount;
    }
}
