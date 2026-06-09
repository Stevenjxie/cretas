package com.cretas.aims.entity;

import com.cretas.aims.entity.BaseEntity;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import lombok.*;
import org.hibernate.annotations.Type;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 整单撤回审批日志 — SP2 核心实体。
 *
 * <p>三层守卫结果驱动 status 流转:
 * <ul>
 *   <li>Guard 通过 + 无历史报工 → status=DONE (直接撤回)</li>
 *   <li>Guard 通过 + 有历史报工 → status=PENDING (需审批)</li>
 *   <li>Guard 不通过 → 不创建 Log, 直接返 409</li>
 * </ul>
 *
 * <p><b>幂等键</b>: UNIQUE(batch_id, reversal_scope)。
 * 已 DONE 的撤回再次请求 → 返 "already-reverted"，不重复执行。
 */
@Entity
@Table(name = "report_reversal_logs",
        uniqueConstraints = @UniqueConstraint(name = "uq_reversal_batch",
                columnNames = {"batch_id", "reversal_scope"}),
        indexes = {
                @Index(name = "idx_rrl_factory_batch", columnList = "factory_id, batch_id"),
                @Index(name = "idx_rrl_status", columnList = "factory_id, status")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportReversalLog extends BaseEntity {

    /** 撤回范围 */
    public enum ReversalScope {
        WHOLE_ORDER
    }

    /** 撤回状态 */
    public enum ReversalStatus {
        /** 有报工数据, 等待主管审批 */
        PENDING,
        /** 审批通过 (可以执行回退事务) */
        APPROVED,
        /** 回退已完成 */
        DONE,
        /** 审批拒绝 */
        REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /** 关联的生产批次 ID */
    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    /** 关联的生产计划 ID (可能为 null: 批次未绑定计划的场景) */
    @Column(name = "plan_id", length = 191)
    private String planId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reversal_scope", nullable = false, length = 20)
    @Builder.Default
    private ReversalScope reversalScope = ReversalScope.WHOLE_ORDER;

    /** 提交撤回申请的操作员 user_id */
    @Column(name = "submitted_by", nullable = false)
    private Long submittedBy;

    /** 审批人 user_id (PENDING→APPROVED/REJECTED 时写入) */
    @Column(name = "approved_by")
    private Long approvedBy;

    /** 撤回理由 */
    @Column(name = "reason", length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ReversalStatus status = ReversalStatus.PENDING;

    /**
     * 本次撤回成功回退的 semi_finished_inventory_transactions.id 列表 (jsonb)。
     * DONE 后写入, 供审计追踪。
     */
    @Type(JsonType.class)
    @Column(name = "reverted_txn_ids", columnDefinition = "jsonb")
    private List<Long> revertedTxnIds;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;
}
