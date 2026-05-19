package com.cretas.aims.service.cron;

import com.cretas.aims.repository.cron.ScheduledTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Canvas-Cron 动态调度核心 (Phase 5 skeleton — body NOT implemented).
 *
 * <p>Implements {@link SchedulingConfigurer}, so Spring calls
 * {@link #configureTasks(ScheduledTaskRegistrar)} once at boot. Sister chat
 * impls this method to:
 *
 * <ol>
 *   <li>Hold {@code taskRegistrar} as a field for later {@link #reload()}.</li>
 *   <li>Load enabled tasks from DB via {@code scheduledTaskRepository.findByEnabledTrue()}.</li>
 *   <li>For each task, resolve {@code handlerBeanName} via {@code applicationContext.getBean(name, TaskHandler.class)}.
 *       If bean is missing, log WARN and SKIP this task (do not abort boot).</li>
 *   <li>Wrap handler call in {@code Runnable} that:
 *       <ul>
 *         <li>Acquires ShedLock (see ShedLock integration note below)</li>
 *         <li>INSERT run log row (status=RUNNING, started_at)</li>
 *         <li>try {@code handler.execute(context)} where context has taskId/taskCode/factoryId/lastRunAt</li>
 *         <li>UPDATE run log + scheduled_tasks row (status=SUCCESS, finished_at, duration_ms)</li>
 *         <li>catch Exception → UPDATE status=FAILED, error_msg=stacktrace</li>
 *       </ul>
 *   </li>
 *   <li>Register via {@code taskRegistrar.addCronTask(runnable, new CronTrigger(cronExpression))}.</li>
 * </ol>
 *
 * <h3>ShedLock integration (sister chat picks one)</h3>
 *
 * <p><b>Option A (recommended):</b> Inject {@code net.javacrumbs.shedlock.core.LockProvider}
 * and manually wrap each Runnable:
 * <pre>{@code
 *   LockConfiguration cfg = new LockConfiguration(Instant.now(),
 *       "scheduled-task-" + task.getTaskCode(),
 *       Duration.ofMinutes(30), Duration.ofSeconds(10));
 *   provider.lock(cfg).ifPresent(lock -> {
 *       try { runnable.run(); } finally { lock.unlock(); }
 *   });
 * }</pre>
 *
 * <p><b>Option B:</b> Wrap the {@link ThreadPoolTaskScheduler} bean in a
 * {@code LockableTaskScheduler} (provided by shedlock-spring) so every task
 * is auto-locked. Slightly more invasive but ergonomic.
 *
 * <p>Either works — Option A is more explicit and self-contained.
 *
 * <h3>Reload</h3>
 *
 * <p>{@link #reload()} (called from {@code DynamicSchedulerServiceImpl} after any
 * REST/AI Tool mutation) should:
 * <ol>
 *   <li>Cancel all currently registered tasks: {@code taskRegistrar.getScheduledTasks().forEach(ScheduledTask::cancel)}</li>
 *   <li>Clear the registrar's task lists (use {@code taskRegistrar.afterPropertiesSet()} or maintain custom Set)</li>
 *   <li>Re-load from DB and re-register (call {@link #configureTasks(ScheduledTaskRegistrar)} again with same instance, OR
 *       extract registration logic into a private helper called from both bootstrap and reload).</li>
 * </ol>
 *
 * @since Phase 5 (2026-05-18)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DynamicScheduler implements SchedulingConfigurer {

    private final ScheduledTaskRepository scheduledTaskRepository;
    private final ApplicationContext applicationContext;
    // private final LockProvider lockProvider;  // sister chat: inject for ShedLock Option A

    /**
     * Held as field so {@link #reload()} can re-register tasks at runtime.
     * Initialized by Spring in {@link #configureTasks(ScheduledTaskRegistrar)}.
     */
    private volatile ScheduledTaskRegistrar taskRegistrar;

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        // Phase 5 sister chat: implement task loading + registration here.
        // See class Javadoc for step-by-step plan.
        //
        // SKELETON: store registrar reference for later reload(), but DO NOT
        // throw — Spring boot must succeed. Sister chat replaces this with the
        // real load-and-register loop. Throwing here would abort app startup.
        this.taskRegistrar = taskRegistrar;
        log.warn("[Canvas-Cron] DynamicScheduler.configureTasks() — Phase 5 skeleton, "
                + "no tasks registered. Sister chat to implement load-from-DB + register loop.");
    }

    /**
     * Reload all tasks from DB. Called by mutation endpoints/Tools after a
     * scheduled_task row is created/updated/toggled/deleted.
     *
     * <p>Phase 5 sister chat: cancel all currently registered tasks, then
     * re-run the registration logic from {@link #configureTasks}.
     */
    public void reload() {
        throw new UnsupportedOperationException(
                "Phase 5 sister chat: cancel current tasks and re-register from DB."
        );
    }
}
