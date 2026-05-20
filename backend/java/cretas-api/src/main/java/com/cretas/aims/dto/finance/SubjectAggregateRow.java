package com.cretas.aims.dto.finance;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 凭证分录按 subjectCode 聚合后的一行结果.
 *
 * <p>Sprint 7 T3 报表三表 (BalanceSheet / IncomeStatement / CashFlow) 的核心查询返回类型.
 *
 * <p>语义:
 * <ul>
 *   <li>{@code totalDebit} = 期间内该科目借方累计</li>
 *   <li>{@code totalCredit} = 期间内该科目贷方累计</li>
 *   <li>净借方余额 = totalDebit - totalCredit (调用方根据 Account.balanceType 决定取正负)</li>
 * </ul>
 *
 * <p>不带 Account.balanceType / category — 调用方 join AccountRepository.findByCodeForFactory
 * 自行映射. 这样 voucher 历史数据兼容 (subjectCode 自由文本, 没绑 Account FK).
 *
 * @since 2026-05-20 Sprint 7 T3
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "凭证按科目聚合一行")
public class SubjectAggregateRow {

    @Schema(description = "科目编码 (e.g. \"1001\" / \"5001\")")
    private String subjectCode;

    @Schema(description = "科目名称 (voucher 录入时的快照)")
    private String subjectName;

    @Schema(description = "借方累计 (entries.debit SUM)")
    private BigDecimal totalDebit;

    @Schema(description = "贷方累计 (entries.credit SUM)")
    private BigDecimal totalCredit;

    @Schema(description = "分录条数")
    private Long entryCount;
}
