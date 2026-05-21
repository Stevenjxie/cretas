package com.cretas.aims.entity.foodsafety;

import com.cretas.aims.entity.BaseEntity;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SSOP 生产阻产 audit 记录 (production gate fail audit trail) — Sprint 9 P2.E.
 *
 * <p>当 production planning 调用 {@code SsopProductionGateCheckTool} 发现当日
 * SSOP 未完成时, 创建此 audit record + 阻产. 第三方 audit (HACCP / FSSC22000)
 * 必查项 — 证明生产前清洁 gate 真实存在 + 启用.
 *
 * <p>{@code productionPlanId} 关联 {@code production_plans.id} (VARCHAR(191) UUID).
 *
 * <p>{@code failedProcedureIds} JSON List of Long — 阻产时未完成的 SSOP record IDs.
 *
 * <p>resolved 流程: 清洁员补做 + 检验员 sign-off → operator 手动 resolve
 * (or scheduler 自动 resolve 当所有 failedProcedureIds 都进入 COMPLETED/SKIPPED).
 *
 * @since 2026-05-21
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "ssop_blocking_gates",
        indexes = {
                @Index(name = "idx_ssop_gates_factory_blocked",
                        columnList = "factory_id,blocked_at"),
                @Index(name = "idx_ssop_gates_plan",
                        columnList = "production_plan_id"),
                @Index(name = "idx_ssop_gates_unresolved",
                        columnList = "factory_id,resolved_at")
        })
@Where(clause = "deleted_at IS NULL")
public class SsopBlockingGate extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /** 工厂 ID. */
    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    /** 关联 production plan. {@code production_plans.id} 是 VARCHAR(191) UUID. */
    @Column(name = "production_plan_id", nullable = false, length = 191)
    private String productionPlanId;

    /** 阻产触发时间. */
    @Column(name = "blocked_at", nullable = false)
    private LocalDateTime blockedAt;

    /** 阻产原因 (e.g. "今日 SSOP-DAILY-LINE-A 未完成 / SSOP-EQUIPMENT-CLEAN 失败"). */
    @Column(name = "block_reason", nullable = false, columnDefinition = "TEXT")
    private String blockReason;

    /** 未完成的 SSOP record IDs (JSONB List of Long). */
    @Type(JsonType.class)
    @Column(name = "failed_procedure_ids", columnDefinition = "jsonb", nullable = false)
    private List<Long> failedProcedureIds;

    /** 解决时间 (清洁员补做 + 检验员 sign-off 后人工标). null = 未解决. */
    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    /** 解决人 user_id. */
    @Column(name = "resolved_by")
    private Long resolvedBy;

    /** 解决备注. */
    @Column(name = "resolved_notes", columnDefinition = "TEXT")
    private String resolvedNotes;
}
