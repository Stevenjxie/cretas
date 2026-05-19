package com.cretas.aims.service.cron;

import com.cretas.aims.entity.cron.ScheduledTask;
import com.cretas.aims.entity.cron.ScheduledTaskRunLog;

import java.util.UUID;

/**
 * Service facade for {@code scheduled_tasks} CRUD + manual run + reload.
 *
 * <p>Phase 5 skeleton — sister chat fills bodies in
 * {@link com.cretas.aims.service.cron.impl.DynamicSchedulerServiceImpl}.
 *
 * <p>Every mutation method should call {@link #reload()} (or
 * {@link DynamicScheduler#reload()}) before returning, so the change takes
 * effect within seconds without JVM restart.
 *
 * @since Phase 5 (2026-05-18)
 */
public interface DynamicSchedulerService {

    /**
     * Re-load tasks from DB into DynamicScheduler. Idempotent.
     */
    void reload();

    /**
     * Trigger a task immediately (synchronous run on caller thread by default —
     * sister chat may switch to async with a returned logId).
     *
     * @param taskId task UUID
     * @return run log row (status SUCCESS or FAILED) of the manual run
     */
    ScheduledTaskRunLog runNow(UUID taskId);

    /**
     * Create a new scheduled task. Validates cron expression syntax, handler
     * bean existence, and partial-unique constraint. Reloads scheduler.
     */
    ScheduledTask createTask(ScheduledTask task);

    /**
     * Partial update. Reloads scheduler if cron / enabled / handler changes.
     */
    ScheduledTask updateTask(UUID taskId, ScheduledTask patch);

    /**
     * Set enabled flag. Reloads scheduler.
     */
    ScheduledTask toggleTask(UUID taskId, boolean enabled);

    /**
     * Soft-delete (sets deleted_at). Reloads scheduler.
     */
    void deleteTask(UUID taskId);
}
