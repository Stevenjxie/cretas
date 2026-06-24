package com.cretas.aims.entity.finance;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 期间结账 (Accounting Period) — Sprint 7 T2 F-PERIOD.
 *
 * <p>大企业财务月底 / 季末 / 年末 关账, 锁定 voucher 写操作以保证 复式记账 数据完整性.
 * Round 12 §G.5 客户需求: HJ 期间结账机制. T1 复式 + 本表 锁定 后, T3 报表 三表 才能可信.
 *
 * <p>状态机:
 * <pre>
 *   OPEN (默认) ──► PENDING_CLOSE (requestClose, 触发审批) ──► CLOSED (confirmClose)
 *       ▲                                                          │
 *       └──────────────────── reopenPeriod (反结账, audit log) ◄────┘
 * </pre>
 *
 * <p>状态语义:
 * <ul>
 *   <li>{@link Status#OPEN}: 期间正常, voucher 可写</li>
 *   <li>{@link Status#PENDING_CLOSE}: 财务发起结账, 等待 finance director 审批 (走 ApprovalWorkflow).
 *       此阶段 voucher 仍可写 — 审批未通过则回 OPEN; 通过则 CLOSED.</li>
 *   <li>{@link Status#CLOSED}: 期间已结账, voucher 写操作被 {@link com.cretas.aims.exception.PeriodClosedException} 拒.
 *       仅 finance director 可 反结账 (reopen) → OPEN</li>
 * </ul>
 *
 * <p>幂等性: {@code (factoryId, year, month)} 三元组 UNIQUE, 重复 openPeriod 不创建新行.
 *
 * <p>Backwards compat: 没有 AccountingPeriod row 的 factory+year+month 视为 OPEN (legacy
 * factories 默认不被 gate). 仅当用户主动调用 openPeriod / requestClose 后才有 row,
 * CLOSED 状态才触发 gate.
 *
 * @since 2026-05-20 (Sprint 7 wave 2 T2)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "accounting_periods",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_accounting_period_factory_year_month",
                        columnNames = {"factory_id", "year", "month"})
        },
        indexes = {
                @Index(name = "idx_ap_factory", columnList = "factory_id"),
                @Index(name = "idx_ap_status", columnList = "status"),
                @Index(name = "idx_ap_year_month", columnList = "year, month")
        })
@Where(clause = "deleted_at IS NULL")
public class AccountingPeriod extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    /** 工厂 ID — multi-tenant 隔离, RLS 在 DB 层. */
    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    /** 会计年度 (e.g. 2026). */
    @Column(name = "year", nullable = false)
    private Integer year;

    /** 会计月份 1-12. */
    @Column(name = "month", nullable = false)
    private Integer month;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    @Builder.Default
    private Status status = Status.OPEN;

    /** 期间打开时间. */
    @Column(name = "opened_at")
    private LocalDateTime openedAt;

    /** 期间打开人. */
    @Column(name = "opened_by")
    private Long openedBy;

    /** 期间关闭时间 (CLOSED 状态). */
    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    /** 期间关闭人 (finance director). */
    @Column(name = "closed_by")
    private Long closedBy;

    /** 反结账时间 (CLOSED → OPEN). */
    @Column(name = "reopened_at")
    private LocalDateTime reopenedAt;

    /** 反结账人. */
    @Column(name = "reopened_by")
    private Long reopenedBy;

    /** 反结账原因 (audit log, free-form text). */
    @Column(name = "reopen_reason", length = 1000)
    private String reopenReason;

    /** 关联的审批工作流实例 id (PENDING_CLOSE 时设, CLOSED 后保留 audit trail). */
    @Column(name = "approval_workflow_instance_id", length = 191)
    private String approvalWorkflowInstanceId;

    // ==================== Wave2 月结自动闭环字段 ====================

    /**
     * 调整窗口截止 (closed_at + 20 天). 兑现邓总 "留20天调整窗口".
     *
     * <p>CLOSED 期间在此之前 voucher 仍可写 (调整窗口内, {@code assertOpen} silent pass);
     * 之后硬锁 (抛 PeriodClosedException). null = 旧 CLOSED 行立即硬锁 (backwards compat).
     */
    @Column(name = "adjust_deadline")
    private LocalDateTime adjustDeadline;

    /** 结转损益凭证已过账时间 (方案A: LOCKED 后 finalize 过账)。NULL=未结转, 反结账时清回 NULL。 */
    @Column(name = "closing_posted_at")
    private LocalDateTime closingPostedAt;

    /** 结账前对账校验结论: PASS | WARNING | null (未执行月结编排). */
    @Column(name = "reconciliation_status", length = 32)
    private String reconciliationStatus;

    /** 对账校验摘要文本 (审计留痕). */
    @Column(name = "reconciliation_summary", length = 2000)
    private String reconciliationSummary;

    /** 结账时冻结的营业收入合计 (来自 IncomeStatement P&L). */
    @Column(name = "total_revenue_snapshot", precision = 18, scale = 2)
    private java.math.BigDecimal totalRevenueSnapshot;

    /** 结账时冻结的净利润 (来自 IncomeStatement P&L). */
    @Column(name = "net_profit_snapshot", precision = 18, scale = 2)
    private java.math.BigDecimal netProfitSnapshot;

    /** 结账时冻结的完整利润表 JSON 快照. 调整窗口内 voucher 变动不影响此快照 (报表冻结). */
    @Column(name = "income_statement_snapshot", columnDefinition = "TEXT")
    private String incomeStatementSnapshot;

    /** 报表生成完成时间 (邓总 "1-3号出报表" 达成标记). */
    @Column(name = "report_ready_at")
    private LocalDateTime reportReadyAt;

    /**
     * 调整窗口状态 (派生, 非持久). 供 UI / voucher gate 判断:
     * <ul>
     *   <li>{@link AdjustWindowState#NOT_CLOSED}: 未结账 (OPEN / PENDING_CLOSE)</li>
     *   <li>{@link AdjustWindowState#OPEN_WINDOW}: 已 CLOSED 且在 adjust_deadline 内, voucher 可调整</li>
     *   <li>{@link AdjustWindowState#LOCKED}: 已 CLOSED 且窗口已过 (或无 deadline), voucher 硬锁</li>
     * </ul>
     */
    @Transient
    public AdjustWindowState getAdjustWindowState() {
        if (status != Status.CLOSED) {
            return AdjustWindowState.NOT_CLOSED;
        }
        if (adjustDeadline != null && LocalDateTime.now().isBefore(adjustDeadline)) {
            return AdjustWindowState.OPEN_WINDOW;
        }
        return AdjustWindowState.LOCKED;
    }

    /**
     * 期间结账状态机.
     */
    public enum Status {
        /** 期间正常, voucher 可自由写入 / 修改 / 过账 / 作废. */
        OPEN,
        /** 财务已发起结账请求, 走 BUDGET_APPROVAL 工作流, 等待 finance director 审批.
         *  此阶段 voucher 仍可写 (审批未通过的回退路径). */
        PENDING_CLOSE,
        /** 期间已正式关账. voucher 写操作抛 PeriodClosedException.
         *  仅反结账 (reopen) 可改回 OPEN. */
        CLOSED
    }

    /**
     * 调整窗口状态 (Wave2 月结). {@link #getAdjustWindowState()} 派生返回.
     */
    public enum AdjustWindowState {
        /** 未结账 (OPEN / PENDING_CLOSE) — voucher 正常可写. */
        NOT_CLOSED,
        /** 已结账且在 20 天调整窗口内 — voucher 仍可调整 (邓总调整窗口). */
        OPEN_WINDOW,
        /** 已结账且窗口已过 (或无 deadline) — voucher 硬锁. */
        LOCKED
    }
}
