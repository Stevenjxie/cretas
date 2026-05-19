package com.cretas.aims.repository.cron;

import com.cretas.aims.entity.cron.ScheduledTaskRunLog;
import com.cretas.aims.entity.cron.TaskRunStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Repository for {@link ScheduledTaskRunLog}.
 *
 * <p>Phase 5 skeleton — sister chat adds custom queries as needed.
 *
 * @since Phase 5 (2026-05-18)
 */
@Repository
public interface ScheduledTaskRunLogRepository extends JpaRepository<ScheduledTaskRunLog, UUID> {

    /**
     * Page of run logs for a task, newest first.
     * Backed by {@code idx_task_logs_task_started (task_id, started_at DESC)}.
     */
    Page<ScheduledTaskRunLog> findByTaskIdOrderByStartedAtDesc(UUID taskId, Pageable pageable);

    /**
     * Count log rows of a given status that started after {@code startedAtAfter}.
     * Used by {@link com.cretas.aims.service.cron.impl.DynamicSchedulerServiceImpl#runNow}
     * to enforce a manual-run rate limit (post-review I3+I8 fix): if a recent
     * manual or scheduled invocation is still RUNNING, refuse a new manual run
     * with HTTP 409 to prevent blue/green double-execute + admin DOS surface.
     */
    long countByTaskIdAndStatusAndStartedAtAfter(UUID taskId, TaskRunStatus status, LocalDateTime startedAtAfter);
}
