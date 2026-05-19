package com.cretas.aims.entity.cron;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 定时任务执行历史 (Phase 5 skeleton).
 *
 * <p>每次 task 执行 (无论 SUCCESS / FAILED / RUNNING / SKIPPED) 写一行。
 * DynamicScheduler 在 task 启动前 INSERT 一行 (status=RUNNING), 完成后 UPDATE
 * (status=SUCCESS|FAILED, finished_at, duration_ms, error_msg).
 *
 * <p>Retention 建议 90 天 — sister chat 决定是用 DB job 还是再起一个 ScheduledTask
 * 自己清理自己 (dog-fooding).
 *
 * @since Phase 5 (2026-05-18)
 */
@Entity
@Table(name = "scheduled_task_run_logs", indexes = {
        @Index(name = "idx_task_logs_task_started",
                columnList = "task_id, started_at DESC")
})
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class ScheduledTaskRunLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * FK → scheduled_tasks(id). No cascade — soft delete of task keeps logs.
     */
    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    /**
     * Denormalized factory_id for fast per-factory filter (avoids JOIN).
     * Mirrors scheduled_tasks.factory_id at task launch time.
     */
    @Column(name = "factory_id", length = 50)
    private String factoryId;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private TaskRunStatus status;

    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;
}
