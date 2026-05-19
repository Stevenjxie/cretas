package com.cretas.aims.service.cron;

import java.util.Map;

/**
 * Handler interface for Canvas-Cron tasks (Phase 5 skeleton).
 *
 * <p>Sister chat creates concrete @Component beans implementing this interface,
 * one per real cron job (e.g. {@code inventoryReportHandler},
 * {@code receivableAgingReminderHandler}). The bean name is referenced from
 * {@code scheduled_tasks.handler_bean_name}.
 *
 * <p>Why an interface (not Runnable): we want to (1) inject context with
 * task metadata (factoryId, taskCode, lastRunAt) so handlers can be reused
 * across factories, and (2) allow checked exceptions, which DynamicScheduler
 * catches and records in the run log.
 *
 * <p>Reference impl: {@link com.cretas.aims.service.cron.handler.EchoTaskHandler}.
 *
 * @since Phase 5 (2026-05-18)
 */
public interface TaskHandler {

    /**
     * Execute the task.
     *
     * @param context Map provided by DynamicScheduler. Keys (sister chat may add more):
     *                <ul>
     *                  <li>{@code taskId} — UUID of the scheduled task</li>
     *                  <li>{@code taskCode} — String task code</li>
     *                  <li>{@code factoryId} — String factory id, or null for global</li>
     *                  <li>{@code lastRunAt} — LocalDateTime of last run, or null on first run</li>
     *                </ul>
     *                Handlers may put output values back into the map for run log capture.
     * @throws Exception any exception is caught by DynamicScheduler, marked FAILED in the
     *                   run log, and recorded in {@code last_run_error}.
     */
    void execute(Map<String, Object> context) throws Exception;
}
