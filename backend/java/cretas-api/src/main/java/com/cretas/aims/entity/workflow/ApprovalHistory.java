package com.cretas.aims.entity.workflow;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 审批历史 — Phase 1 Canvas-Workflow Step B.3 审计/时间线/报表.
 *
 * <p>每次 workflow transition 一条 append-only 记录. 不继承 {@code BaseEntity}
 * (无 updated_at / deleted_at — 历史记录不可改不可删).
 *
 * <p>用途:
 * <ol>
 *   <li><b>审计</b> — F006 客户合规要求所有审批可追溯</li>
 *   <li><b>时间线 UI</b> — Canvas Approval Tab 内嵌"实例历史"面板</li>
 *   <li><b>Phase D 报表</b> — 通过率 / 平均时长 / 卡瓶颈节点</li>
 * </ol>
 *
 * <p>{@code instance_id} 引用 {@link ApprovalWorkflowInstance#getId} 但
 * <b>NOT FK</b> — instance 归档/删除时 history 保留作历史快照.
 *
 * <p>Maps to table {@code approval_history} (V20260607_01).
 *
 * @since 2026-05-18 (Phase 1 B.3)
 */
@Entity
@Table(name = "approval_history",
       indexes = {
           @Index(name = "idx_approval_history_instance_time",
                  columnList = "factory_id, instance_id, created_at"),
           @Index(name = "idx_approval_history_factory_action_time",
                  columnList = "factory_id, action, created_at")
       })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalHistory {

    /** Auto-generated BIGSERIAL — append-only id. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    /** 关联 {@link ApprovalWorkflowInstance#getId}. NOT FK (见 javadoc). */
    @Column(name = "instance_id", length = 36, nullable = false)
    private String instanceId;

    /** 触发本次 transition 的节点 id (从 workflow graph). */
    @Column(name = "node_id", length = 50, nullable = false)
    private String nodeId;

    /** Action 类型 — 见 {@link HistoryAction}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "action", length = 32, nullable = false)
    private HistoryAction action;

    /** 操作人 userId. {@code AUTO_TRANSITION / START} 时 NULL. */
    @Column(name = "actor_id")
    private Long actorId;

    /** 操作人 role (e.g. {@code "factory_super_admin"}). {@code AUTO_TRANSITION / START} 时 NULL. */
    @Column(name = "actor_role", length = 50)
    private String actorRole;

    /** 自由文本备注 (审批意见 / 驳回理由 / 委托人姓名). */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /**
     * 从前一节点到本节点的耗时 (秒). {@code START} 时 NULL.
     *
     * <p>用于 Phase D "平均审批时长 / 卡瓶颈节点" 报表.
     */
    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    /**
     * History action enum.
     *
     * <ul>
     *   <li>{@code APPROVE} — 用户审批通过</li>
     *   <li>{@code REJECT} — 用户驳回</li>
     *   <li>{@code SKIP} — 用户跳过 (允许跳过的节点)</li>
     *   <li>{@code DELEGATE} — 用户委托给他人</li>
     *   <li>{@code TIMEOUT} — 超时自动 escalate</li>
     *   <li>{@code START} — workflow 实例启动 (actor=NULL)</li>
     *   <li>{@code AUTO_TRANSITION} — condition/parallel 自动推进 (actor=NULL)</li>
     *   <li>{@code CANCEL} — workflow 取消</li>
     * </ul>
     */
    public enum HistoryAction {
        APPROVE,
        REJECT,
        SKIP,
        DELEGATE,
        TIMEOUT,
        START,
        AUTO_TRANSITION,
        CANCEL
    }
}
