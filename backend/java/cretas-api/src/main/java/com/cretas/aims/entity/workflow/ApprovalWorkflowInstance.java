package com.cretas.aims.entity.workflow;

import com.cretas.aims.entity.BaseEntity;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 审批工作流实例 — Phase 1 Canvas-Workflow Step B.3 持久化.
 *
 * <p>替换 Sprint 3 Track-I {@code ApprovalWorkflowExecutorImpl} 的内存
 * {@code ConcurrentHashMap}. 持久化策略:
 * <ul>
 *   <li><b>Redis</b> — hot cache (key {@code aw:instance:{id}}), 实时读写</li>
 *   <li><b>PostgreSQL</b> — shadow store, 重启恢复 + 跨实例查询 + 归档历史</li>
 * </ul>
 *
 * <p>启动时 {@code WorkflowEngineServiceImpl#rebuildRedisFromPg} 扫
 * {@code status=RUNNING} 行重建 Redis state. 终态后 Redis TTL 过期,
 * PG 保留作历史.
 *
 * <p>Maps to table {@code approval_workflow_instances} (V20260607_02).
 *
 * @since 2026-05-18 (Phase 1 B.3)
 */
@Entity
@Table(name = "approval_workflow_instances",
       indexes = {
           @Index(name = "idx_aw_instances_business_entity",
                  columnList = "factory_id, module_code, business_entity_id"),
           @Index(name = "idx_aw_instances_recovery",
                  columnList = "factory_id, status"),
           @Index(name = "idx_aw_instances_workflow_stats",
                  columnList = "factory_id, workflow_id, status, completed_at"),
           // Sprint 5 Track A: personal view "我创建的工作流" — V20260625_01 partial index
           @Index(name = "idx_aw_instances_initiated_by",
                  columnList = "factory_id, initiated_by")
       })
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalWorkflowInstance extends BaseEntity {

    /** Instance id — UUID. 由 service 层生成 (UUID.randomUUID().toString()). */
    @Id
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private String id;

    @Column(name = "factory_id", length = 50, nullable = false)
    private String factoryId;

    /** 关联 workflow 配置 id. NOT FK — 允许 workflow archive 但 instance 保留. */
    @Column(name = "workflow_id", length = 36, nullable = false)
    private String workflowId;

    /**
     * 业务模块 code — 决定 startWorkflow 时映射到哪个 DecisionType.
     *
     * <p>Phase 1: {@code "PURCHASE_ORDER"}, {@code "SALES_ORDER"}.
     * Phase B+: 扩 DISPOSAL / TRANSFER / VOUCHER / ECN 等.
     */
    @Column(name = "module_code", length = 50, nullable = false)
    private String moduleCode;

    /**
     * 业务实体 id — PO id / SO id 等. VARCHAR(191) 兼容长 UUID 或复合 id.
     */
    @Column(name = "business_entity_id", length = 191, nullable = false)
    private String businessEntityId;

    /** Instance 状态. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    @Builder.Default
    private InstanceStatus status = InstanceStatus.RUNNING;

    /**
     * 当前 active 节点 ids — JSONB array. 支持 parallel 多分支并行.
     *
     * <p>e.g. {@code ["approval_finance"]} 单分支 / {@code ["parallel_1", "parallel_2"]} 并行.
     */
    @Type(JsonBinaryType.class)
    @Column(name = "current_node_ids", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private List<String> currentNodeIds = new ArrayList<>();

    /**
     * 业务 context — JSONB. SpEL condition 评估时绑定为 {@code #context.xxx}.
     *
     * <p>e.g. PO context: {@code {amount, supplier, priceVariance, items: [...]}}.
     */
    @Type(JsonBinaryType.class)
    @Column(name = "context_json", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private Map<String, Object> contextJson = new HashMap<>();

    /** 发起人 userId. 系统自动触发时为 NULL. */
    @Column(name = "initiated_by")
    private Long initiatedBy;

    @Column(name = "initiated_at", nullable = false)
    private LocalDateTime initiatedAt;

    /** 终态时间戳 (APPROVED/REJECTED/CANCELLED/TIMEOUT 时填). RUNNING 时 NULL. */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /**
     * 乐观锁版本号 (issue #12 Phase 1 follow-up).
     *
     * <p>防御并发 APPROVE 重复转换: 2 个 reviewer 同时 transition → 第二个 UPDATE
     * 命中 stale version, Hibernate 抛 {@link org.springframework.orm.ObjectOptimisticLockingFailureException}.
     * Service 层捕获后 → {@link com.cretas.aims.exception.BusinessException} 409
     * "并发审批冲突, 请刷新后重试".
     *
     * <p>Migration: {@code V20260519_02__add_version_to_approval_workflow_instances.sql}
     * 添加 {@code version BIGINT NOT NULL DEFAULT 0} 列.
     *
     * @since 2026-05-19 (issue #12 fix)
     */
    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

    @PrePersist
    protected void onPersistInstance() {
        if (initiatedAt == null) {
            initiatedAt = LocalDateTime.now();
        }
        if (currentNodeIds == null) {
            currentNodeIds = new ArrayList<>();
        }
        if (contextJson == null) {
            contextJson = new HashMap<>();
        }
    }

    /**
     * Instance 状态机.
     *
     * <ul>
     *   <li>{@code RUNNING} — 至少 1 个节点 active 等待人工/超时</li>
     *   <li>{@code APPROVED} — 走到 type=end outcome=APPROVED</li>
     *   <li>{@code REJECTED} — 走到 type=end outcome=REJECTED 或 approval reject</li>
     *   <li>{@code CANCELLED} — 业务发起方主动 cancel</li>
     *   <li>{@code TIMEOUT} — 自动 escalate 多次失败后终态</li>
     * </ul>
     */
    public enum InstanceStatus {
        RUNNING,
        APPROVED,
        REJECTED,
        CANCELLED,
        TIMEOUT
    }
}
