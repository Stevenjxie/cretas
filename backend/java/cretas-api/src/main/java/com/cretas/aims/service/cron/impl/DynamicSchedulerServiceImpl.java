package com.cretas.aims.service.cron.impl;

import com.cretas.aims.entity.cron.ScheduledTask;
import com.cretas.aims.entity.cron.ScheduledTaskRunLog;
import com.cretas.aims.repository.cron.ScheduledTaskRepository;
import com.cretas.aims.repository.cron.ScheduledTaskRunLogRepository;
import com.cretas.aims.service.cron.DynamicScheduler;
import com.cretas.aims.service.cron.DynamicSchedulerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Implementation of {@link DynamicSchedulerService} — Phase 5 skeleton.
 *
 * <p>Sister chat fills all method bodies. See {@link DynamicScheduler} Javadoc
 * for the impl plan. Notes for sister chat:
 *
 * <ul>
 *   <li>{@link #createTask} / {@link #updateTask}: validate cron with
 *       {@code org.springframework.scheduling.support.CronExpression.parse(cron)} —
 *       throw {@code BusinessException(400, ...)} with {@code actionHint} if invalid.</li>
 *   <li>{@link #updateTask}: validate {@code handler_bean_name} exists via
 *       {@code applicationContext.containsBean(name)} AND is a {@code TaskHandler}.</li>
 *   <li>{@link #runNow}: build the context map (same shape as DynamicScheduler), call
 *       handler.execute() inline, persist a run_log row, and return it.</li>
 *   <li>Every mutation calls {@link DynamicScheduler#reload()} BEFORE returning
 *       (so the caller sees the new state in the next tick).</li>
 * </ul>
 *
 * @since Phase 5 (2026-05-18)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicSchedulerServiceImpl implements DynamicSchedulerService {

    private final ScheduledTaskRepository taskRepository;
    private final ScheduledTaskRunLogRepository runLogRepository;
    private final DynamicScheduler dynamicScheduler;

    @Override
    public void reload() {
        throw new UnsupportedOperationException(
                "Phase 5 sister chat: delegate to dynamicScheduler.reload()."
        );
    }

    @Override
    public ScheduledTaskRunLog runNow(UUID taskId) {
        throw new UnsupportedOperationException(
                "Phase 5 sister chat: load task, resolve handler bean, execute inline, persist run log."
        );
    }

    @Override
    public ScheduledTask createTask(ScheduledTask task) {
        throw new UnsupportedOperationException(
                "Phase 5 sister chat: validate cron + handler bean + uniqueness, then save + reload()."
        );
    }

    @Override
    public ScheduledTask updateTask(UUID taskId, ScheduledTask patch) {
        throw new UnsupportedOperationException(
                "Phase 5 sister chat: partial update fields, validate, save, reload() if cron/enabled/handler changed."
        );
    }

    @Override
    public ScheduledTask toggleTask(UUID taskId, boolean enabled) {
        throw new UnsupportedOperationException(
                "Phase 5 sister chat: set enabled flag, save, reload()."
        );
    }

    @Override
    public void deleteTask(UUID taskId) {
        throw new UnsupportedOperationException(
                "Phase 5 sister chat: soft-delete (Repository.deleteById triggers @SQLDelete), then reload()."
        );
    }
}
