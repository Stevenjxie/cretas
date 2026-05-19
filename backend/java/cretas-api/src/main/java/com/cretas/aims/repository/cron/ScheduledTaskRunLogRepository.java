package com.cretas.aims.repository.cron;

import com.cretas.aims.entity.cron.ScheduledTaskRunLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
