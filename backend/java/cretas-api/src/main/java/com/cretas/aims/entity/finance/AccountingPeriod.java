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
}
