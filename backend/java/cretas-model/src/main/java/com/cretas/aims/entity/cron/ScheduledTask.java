package com.cretas.aims.entity.cron;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Canvas-Cron 定时任务配置 (Phase 5 skeleton).
 *
 * <p>每行 = 一个 DB-driven cron task。 DynamicScheduler 启动时从此表加载所有
 * {@code enabled = true} 的记录, 按 {@link #cronExpression} 注册到 ThreadPoolTaskScheduler,
 * 触发时调用 {@link #handlerBeanName} 对应的 Spring bean (必须实现
 * {@code com.cretas.aims.service.cron.TaskHandler}).
 *
 * <p>{@code factoryId} 语义:
 * <ul>
 *   <li>NULL → global task (跨工厂; e.g. cache eviction, AI weight adjustment)</li>
 *   <li>非 NULL → per-factory task (e.g. factory F006 月度库存报表)</li>
 * </ul>
 *
 * <p>唯一约束 (partial index, see V20260624_01__create_scheduled_tasks.sql):
 * <ul>
 *   <li>{@code (task_code) WHERE factory_id IS NULL AND deleted_at IS NULL} — global 唯一</li>
 *   <li>{@code (factory_id, task_code) WHERE factory_id IS NOT NULL AND deleted_at IS NULL}
 *       — per-factory 唯一</li>
 * </ul>
 *
 * @since Phase 5 (2026-05-18)
 */
@Entity
@Table(name = "scheduled_tasks", indexes = {
        @Index(name = "idx_scheduled_tasks_enabled", columnList = "enabled")
})
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class ScheduledTask extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * NULL = global task (跨工厂); 非 NULL = per-factory.
     */
    @Column(name = "factory_id", length = 50)
    private String factoryId;

    /**
     * 任务代码 — 全局或 per-factory 唯一 (partial unique index).
     */
    @Column(name = "task_code", length = 100, nullable = false)
    private String taskCode;

    /**
     * 人类可读名称, 显示在 Canvas UI / AI confirmation.
     */
    @Column(name = "task_name", length = 255)
    private String taskName;

    /**
     * Spring cron 表达式 (6 字段: 秒 分 时 日 月 周).
     * e.g. "0 0 9 1 * ?" = 每月 1 日 09:00:00 触发.
     */
    @Column(name = "cron_expression", length = 100, nullable = false)
    private String cronExpression;

    /**
     * Spring bean name 必须实现 {@code com.cretas.aims.service.cron.TaskHandler}.
     * DynamicScheduler 启动时验证 bean 存在, 否则 task SKIPPED.
     */
    @Column(name = "handler_bean_name", length = 255, nullable = false)
    private String handlerBeanName;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    /**
     * String form of {@link TaskRunStatus} for last execution.
     * Stored as VARCHAR(20) for backward-compat with raw SQL queries.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "last_run_status", length = 20)
    private TaskRunStatus lastRunStatus;

    @Column(name = "last_run_error", columnDefinition = "TEXT")
    private String lastRunError;

    /**
     * AUD-4 P1 (edge audit 2026-05-20): JPA optimistic locking version.
     *
     * <p>Hibernate manages this column automatically — first save inserts
     * with {@code version=0}, every subsequent {@code save()} or
     * {@code merge()} increments it. If two parallel PUT requests load
     * the same row (both see {@code version=N}), Hibernate's flush detects
     * the stale snapshot on the loser and throws
     * {@link org.springframework.orm.ObjectOptimisticLockingFailureException}.
     * {@link com.cretas.aims.exception.GlobalExceptionHandler} catches it
     * and surfaces a 409 with the actionHint
     * "请刷新页面查看最新数据后再编辑" — the user can retry without
     * silently losing the other user's edits (Lost Update prevention).
     *
     * <p>Column is added via Flyway {@code V20260626_02} with
     * {@code DEFAULT 0 NOT NULL}; backfill is implicit because the migration
     * runs before any code reads/writes the column.
     *
     * <p><b>Note on lastRunAt / lastRunStatus writes</b>: the scheduler
     * service writes these audit columns after each cron tick. If a parallel
     * PUT update happens to land between the load and save inside a run-now
     * flow, the loser will retry; concurrent ticks on the same task are
     * already serialized by {@code @SchedulerLock} so the optimistic-lock
     * collision domain is limited to user-driven mutations.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
