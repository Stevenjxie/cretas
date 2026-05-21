package com.cretas.aims.entity.foodsafety;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;

/**
 * 召回事件关联行动记录 — Sprint 8 P3 Phase A.
 *
 * <p>每个 {@link RecallEvent} 关联多个行动 (一对多). action_type 涵盖:
 * - FREEZE_INVENTORY 库存冻结 (target: 物料/产品批次 ID)
 * - NOTIFY_CUSTOMER 客户通知 (target: 客户 ID, 含短信/微信)
 * - REGULATORY_REPORT 监管上报 (target: 监管机构记录 ID)
 * - CUSTOMER_REPLY 客户回复 (闭环跟踪)
 * - COMPLETION_AUDIT 完成审计 (status=COMPLETED 时记录)
 *
 * <p>每个 action 独立追踪 status (PENDING → COMPLETED / FAILED) +
 * executed_at (实际执行时间) + execution_notes (备注).
 *
 * <p>查询 hot path: 按 recall_event_id 查全部 actions (UI dashboard).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "recall_actions",
        indexes = {
                @Index(name = "idx_recall_actions_event",
                        columnList = "recall_event_id,created_at"),
                @Index(name = "idx_recall_actions_status",
                        columnList = "recall_event_id,status")
        })
@Where(clause = "deleted_at IS NULL")
public class RecallAction extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /** {@link RecallEvent#id} 引用. */
    @Column(name = "recall_event_id", nullable = false)
    private Long recallEventId;

    /**
     * 行动类型:
     * - FREEZE_INVENTORY (库存冻结)
     * - NOTIFY_CUSTOMER (客户通知)
     * - REGULATORY_REPORT (监管上报)
     * - CUSTOMER_REPLY (客户回复)
     * - COMPLETION_AUDIT (完成审计)
     */
    @Column(name = "action_type", nullable = false, length = 50)
    private String actionType;

    /** 目标实体类型 (e.g. "CUSTOMER", "BATCH", "SUPPLIER"). */
    @Column(name = "target_entity_type", nullable = false, length = 30)
    private String targetEntityType;

    /** 目标实体 ID (target_entity_type 对应 entity 的 ID). */
    @Column(name = "target_entity_id", nullable = false)
    private Long targetEntityId;

    /** 行动状态: PENDING (待执行) / COMPLETED (已完成) / FAILED (失败). */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    /** 实际执行时间 (status=COMPLETED 时填). */
    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    /** 执行备注 (e.g. "已发送 12 条短信", "回复邮件已收悉"). */
    @Column(name = "execution_notes", columnDefinition = "TEXT")
    private String executionNotes;
}
