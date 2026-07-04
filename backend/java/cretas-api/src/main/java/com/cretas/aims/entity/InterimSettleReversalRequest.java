package com.cretas.aims.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 撤销小结申请 (interim-settle reversal request) — 申请→审批→执行 治理实体。
 *
 * <p>撤销小结不再是免审即时动作: 逐道文员发起<b>撤销申请</b> (PENDING_APPROVAL, <b>零库存副作用</b> —— 行仍 已小结,
 * 库存不动), 经 {@code STOCKTAKE_APPROVAL_ROLES} (finance_manager/factory_super_admin/platform_admin) 审批后,
 * 审批动作内联执行 {@code InterimSettleReversalService.reverseInterimSettle} (悲观锁 + 下游守卫 + 原子, 不变)。
 * 驳回则关闭申请, 零副作用。
 *
 * <p><b>本行即操作留痕</b> (requestedBy/reason/approvedBy/approvedAt/executedAt) + 执行侧 SFI REVERSE 流水
 * + FinishedGoodsAdjustmentLog(INTERIM_SETTLE_REVERSAL) 共同构成审计。可按 plan / batch / period 查询。
 *
 * <p>🔒 红线: 治理真实库存逆转 + 审批权限 + 多租户。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "interim_settle_reversal_request",
        indexes = {
                @Index(name = "idx_isrr_factory_plan", columnList = "factory_id, production_plan_id"),
                @Index(name = "idx_isrr_factory_status", columnList = "factory_id, status"),
                @Index(name = "idx_isrr_executed_at", columnList = "factory_id, executed_at")
        })
@Where(clause = "deleted_at IS NULL")
public class InterimSettleReversalRequest extends BaseEntity {

    /** 申请状态。 */
    public enum Status {
        /** 已提交, 待审批 (零库存副作用)。 */
        PENDING_APPROVAL,
        /** 审批通过并已执行逆转。 */
        EXECUTED,
        /** 驳回, 零副作用。 */
        REJECTED
    }

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "production_plan_id", nullable = false, length = 50)
    private String productionPlanId;

    @Column(name = "session_seq", nullable = false)
    private Integer sessionSeq;

    /** 1天时间窗锚点: 小结 postedAt 快照 (非申请时间); 申请 + 审批 两端都据此校验 24h。 */
    @Column(name = "settlement_posted_at", nullable = false)
    private LocalDateTime settlementPostedAt;

    @Column(name = "reason", nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private Status status;

    @Column(name = "requested_by")
    private Long requestedBy;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "reject_reason", columnDefinition = "TEXT")
    private String rejectReason;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    /** 执行时快照被逆转影响的半成品/成品批次号 (逗号分隔), 供半成品盘点列撤销告警 (settlement 已硬删故快照)。 */
    @Column(name = "affected_batch_numbers", columnDefinition = "TEXT")
    private String affectedBatchNumbers;

    /**
     * #1214 静默漂移缺口修复: 执行时快照 —— 撤销小结连带冲销的同厂调拨 (TRF-child) 记录操作提示 (分号分隔)。
     * 每条提示含调拨单号 + 冲销数量 + "请核实/退回物理货物" 指引 (fool-proof Rule 2/5, 见
     * {@code FinishedGoodsFeedServiceImpl#reconcileTransferForRetiredChild})。无连带冲销 → null。
     */
    @Column(name = "transfer_reconcile_hints", columnDefinition = "TEXT")
    private String transferReconcileHints;

    /**
     * 复用 INVENTORY_ADJUSTMENT workflow 实例 ID (镜像半成品盘点 {@code SemiFinishedStocktake.workflowInstanceId})。
     * 申请创建时登记进统一审批中心列表; 审批/驳回时驱动该实例到终态。可空: 工厂无 active workflow 时不登记 (向后兼容)。
     */
    @Column(name = "workflow_instance_id", length = 191)
    private String workflowInstanceId;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (requestedAt == null) {
            requestedAt = LocalDateTime.now();
        }
        if (status == null) {
            status = Status.PENDING_APPROVAL;
        }
    }
}
