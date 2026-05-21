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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * SSOP 清洁执行记录 (每日实际操作) — Sprint 9 P2.E.
 *
 * <p>每日 6am scheduler 按 {@link SsopProcedure} (active + frequency=DAILY) 自动
 * 创建 SCHEDULED 状态的 records. 清洁员完成后人工标 COMPLETED + 检验员 sign-off.
 *
 * <p>shift (班次):
 * <ul>
 *   <li>{@code MORNING} — 早班 (8:00-16:00)</li>
 *   <li>{@code AFTERNOON} — 中班 (16:00-24:00)</li>
 *   <li>{@code NIGHT} — 夜班 (00:00-8:00)</li>
 * </ul>
 *
 * <p>status (状态机, 单向迁移):
 * <pre>
 *   SCHEDULED ─→ IN_PROGRESS ─→ COMPLETED (sign-off 完成)
 *      │                │
 *      │                └─→ FAILED (执行中检验员判不合格)
 *      └─→ SKIPPED (人工标 e.g. 设备停机不清洁)
 * </pre>
 *
 * <p>{@code inspectorUserId} 必须由具有该模板 {@code inspectorRole} 的用户填写.
 * sign-off 后 status 锁定不可改 (audit trail).
 *
 * <p>Production gate (per {@code SsopProductionGateCheckTool}): 当日 production
 * 开工前查 (factoryId, executionDate=today, status NOT IN (COMPLETED, SKIPPED))
 * 任一存在 → 阻产.
 *
 * @since 2026-05-21
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "ssop_execution_records",
        indexes = {
                @Index(name = "idx_ssop_records_factory_date_status",
                        columnList = "factory_id,execution_date,status"),
                @Index(name = "idx_ssop_records_procedure_date",
                        columnList = "procedure_id,execution_date"),
                @Index(name = "idx_ssop_records_executor",
                        columnList = "factory_id,executor_user_id,execution_date")
        })
@Where(clause = "deleted_at IS NULL")
public class SsopExecutionRecord extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /** 工厂 ID. */
    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    /** 关联模板 {@link SsopProcedure}. */
    @Column(name = "procedure_id", nullable = false)
    private Long procedureId;

    /** 执行日期. */
    @Column(name = "execution_date", nullable = false)
    private LocalDate executionDate;

    /** 计划/实际执行时间 (HH:mm:ss). 可选, 默认按 shift 时段. */
    @Column(name = "execution_time")
    private LocalTime executionTime;

    /** 清洁员 user_id (执行人). SCHEDULED 状态可为 null (尚未分配). */
    @Column(name = "executor_user_id")
    private Long executorUserId;

    /** 班次: MORNING / AFTERNOON / NIGHT. */
    @Column(name = "shift", nullable = false, length = 20)
    private String shift;

    /** 状态: SCHEDULED / IN_PROGRESS / COMPLETED / FAILED / SKIPPED. */
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private String status = "SCHEDULED";

    /** 完成时间 (COMPLETED / FAILED 时填). */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** 检验员 user_id (sign-off 人, COMPLETED 时填). */
    @Column(name = "inspector_user_id")
    private Long inspectorUserId;

    /** 检验员签字时间. */
    @Column(name = "inspector_signed_at")
    private LocalDateTime inspectorSignedAt;

    /** 备注 (e.g. SKIPPED 原因 / FAILED 原因 / 检验员意见). */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
