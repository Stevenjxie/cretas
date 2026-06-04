package com.cretas.aims.dto.finance;

import com.cretas.aims.dto.finance.report.IncomeStatementDTO;
import com.cretas.aims.entity.finance.AccountingPeriod;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Wave2 月结执行结果 DTO.
 *
 * <p>{@code executeClose} 成功后返回: 已 CLOSED 的 period (含调整窗口截止) +
 * 结账时生成的利润表 (P&L) + 报表生成时间。兑现邓总 "1-3号出报表, 留20天调整窗口"。
 *
 * <p>前端拿到后: dead-end 导航 (防呆 Rule 5) 提示 "报表已生成, 是否查看利润表?" → 跳报表页。
 *
 * @since 2026-06-04 Wave2 月结自动闭环
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MonthCloseResultDTO {

    /** 已结账的期间 (含 closedAt / adjustDeadline / 快照字段). */
    private AccountingPeriod period;

    /** 结账时生成的利润表 (P&L). 调整窗口内 voucher 变动不影响此快照 (报表冻结). */
    private IncomeStatementDTO incomeStatement;

    /** 调整窗口截止时间 (closed_at + 20 天). */
    private LocalDateTime adjustDeadline;

    /** 报表生成完成时间 (邓总 "1-3号出报表" 达成标记). */
    private LocalDateTime reportReadyAt;

    /** 对账结论 PASS | WARNING. */
    private String reconciliationStatus;

    /** 给用户的成功消息. */
    private String message;
}
