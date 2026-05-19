package com.cretas.aims.service.cron;

import com.cretas.aims.entity.cron.ScheduledTask;
import com.cretas.aims.entity.cron.ScheduledTaskRunLog;
import com.cretas.aims.entity.cron.TaskRunStatus;
import com.cretas.aims.repository.cron.ScheduledTaskRepository;
import com.cretas.aims.repository.cron.ScheduledTaskRunLogRepository;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;

/**
 * Canvas-Cron 动态调度核心.
 *
 * <p>实现 {@link SchedulingConfigurer}, Spring 启动时调用一次
 * {@link #configureTasks(ScheduledTaskRegistrar)}, 加载 DB 中所有
 * {@code enabled = true} 的 task 并注册到 {@link ThreadPoolTaskScheduler}.
 *
 * <p>每个 task runnable 用 ShedLock {@link LockProvider} 包一层 (Option A):
 * <ul>
 *   <li>多 JVM 同时跑 cron tick → 只有一个拿到锁, 其余 silently skip</li>
 *   <li>lockAtMostFor = 10min (超过则其他实例可 retry, 防 stuck JVM)</li>
 *   <li>lockAtLeastFor = 30s (clock skew protection)</li>
 * </ul>
 *
 * <p>每次执行写 {@code scheduled_task_run_logs}:
 * <ol>
 *   <li>INSERT 一行 status=RUNNING + startedAt</li>
 *   <li>handler.execute() success → UPDATE status=SUCCESS, finishedAt, durationMs</li>
 *   <li>handler.execute() throw → UPDATE status=FAILED + errorMsg (stack trace, 截断到
 *       {@value #ERROR_MSG_MAX_LEN} 字符, 末尾追加 "...[truncated]" 标记)</li>
 *   <li>同步更新 scheduled_tasks.last_run_* 字段</li>
 * </ol>
 *
 * <p>{@link #reload()}: cancel current tasks (维护的 ScheduledFuture list) + 重跑注册逻辑.
 *
 * @since Phase 5 (2026-05-18)
 */
@Slf4j
@Component
public class DynamicScheduler implements SchedulingConfigurer {

    /**
     * Canonical cap for error_msg / last_run_error persistence. Post-review C2
     * unified previously-mixed 2000 / 3500 / 4000 callsites to one value with a
     * trailing "...[truncated]" marker so consumers know they're seeing a tail-cut.
     *
     * <p>Both {@code scheduled_tasks.last_run_error} (TEXT) and
     * {@code scheduled_task_run_logs.error_msg} (TEXT) accept arbitrary length,
     * but we cap at 4000 for log-readability + index-friendly DB pages.
     */
    static final int ERROR_MSG_MAX_LEN = 4000;
    private static final String TRUNCATION_MARKER = "...[truncated]";

    private final ScheduledTaskRepository scheduledTaskRepository;
    private final ScheduledTaskRunLogRepository runLogRepository;
    private final ApplicationContext applicationContext;
    private final LockProvider lockProvider;
    private final ThreadPoolTaskScheduler taskScheduler;

    /**
     * Held as field so {@link #reload()} can re-register tasks at runtime.
     */
    private volatile ScheduledTaskRegistrar taskRegistrar;

    /**
     * Track all currently registered ScheduledFutures so reload() can cancel them.
     * Use CopyOnWriteArrayList for thread-safe iteration during cancel.
     */
    private final List<ScheduledFuture<?>> activeFutures = new CopyOnWriteArrayList<>();

    public DynamicScheduler(ScheduledTaskRepository scheduledTaskRepository,
                            ScheduledTaskRunLogRepository runLogRepository,
                            ApplicationContext applicationContext,
                            LockProvider lockProvider) {
        this.scheduledTaskRepository = scheduledTaskRepository;
        this.runLogRepository = runLogRepository;
        this.applicationContext = applicationContext;
        this.lockProvider = lockProvider;
        // Lazy-init a dedicated scheduler — avoids overlap with engine.DynamicSchedulerService
        // (different package, different config-source) and Spring default task scheduler.
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("canvas-cron-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        this.taskScheduler = scheduler;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        this.taskRegistrar = taskRegistrar;
        taskRegistrar.setTaskScheduler(taskScheduler);
        registerAllEnabledTasks();
    }

    /**
     * Load enabled tasks from DB and register each to the scheduler.
     * Idempotent — callers must cancel current futures first if re-running.
     */
    private void registerAllEnabledTasks() {
        if (taskRegistrar == null) {
            log.warn("[Canvas-Cron] registerAllEnabledTasks called before taskRegistrar wired — skipping.");
            return;
        }
        List<ScheduledTask> tasks = scheduledTaskRepository.findByEnabledTrue();
        int registered = 0;
        int skipped = 0;
        for (ScheduledTask task : tasks) {
            try {
                Runnable runnable = buildRunnable(task);
                CronTask cronTask = new CronTask(runnable, task.getCronExpression());
                ScheduledFuture<?> future = taskScheduler.schedule(
                        cronTask.getRunnable(),
                        cronTask.getTrigger()
                );
                if (future != null) {
                    activeFutures.add(future);
                    registered++;
                }
                log.info("[Canvas-Cron] Registered task: taskCode={} cron={} handler={} factoryId={}",
                        task.getTaskCode(), task.getCronExpression(),
                        task.getHandlerBeanName(), task.getFactoryId());
            } catch (Exception e) {
                skipped++;
                log.warn("[Canvas-Cron] Failed to register task {}: {}",
                        task.getTaskCode(), e.getMessage());
            }
        }
        log.info("[Canvas-Cron] DynamicScheduler initialized: registered={}, skipped={}, total={}",
                registered, skipped, tasks.size());
    }

    /**
     * Build the Runnable for a task. Wraps:
     * <ol>
     *   <li>ShedLock acquire (lockName = "scheduled-task-" + taskCode)</li>
     *   <li>resolve handler bean by name (fail-loud if missing at runtime)</li>
     *   <li>create RUNNING run log row</li>
     *   <li>handler.execute() with context map</li>
     *   <li>update run log + scheduled_task.last_run_* on success/failure</li>
     * </ol>
     */
    private Runnable buildRunnable(ScheduledTask task) {
        final UUID taskId = task.getId();
        final String taskCode = task.getTaskCode();
        final String factoryId = task.getFactoryId();
        final String handlerBeanName = task.getHandlerBeanName();

        return () -> {
            LockConfiguration lockConfig = new LockConfiguration(
                    Instant.now(),
                    "scheduled-task-" + taskCode,
                    Duration.ofMinutes(10),
                    Duration.ofSeconds(30)
            );
            Optional<SimpleLock> lock = lockProvider.lock(lockConfig);
            if (lock.isEmpty()) {
                log.debug("[Canvas-Cron] taskCode={} skipped (lock held by another instance)", taskCode);
                return;
            }
            try {
                executeWithLogging(taskId, taskCode, factoryId, handlerBeanName);
            } finally {
                // Post-review I4: swallow unlock failures — propagating here would
                // suppress the original task exception (if any) and obscure the real
                // failure root cause. Worst case: lock auto-releases at lockAtMostFor.
                try {
                    lock.get().unlock();
                } catch (Exception unlockErr) {
                    log.warn("[Canvas-Cron] ShedLock unlock failed for taskCode={}: {}",
                            taskCode, unlockErr.getMessage());
                }
            }
        };
    }

    /**
     * Execute handler synchronously and record run log + scheduled_task status.
     * Called from inside ShedLock-protected block.
     */
    private void executeWithLogging(UUID taskId, String taskCode, String factoryId, String handlerBeanName) {
        LocalDateTime startedAt = LocalDateTime.now();
        long startNanos = System.nanoTime();

        ScheduledTaskRunLog runLog = ScheduledTaskRunLog.builder()
                .taskId(taskId)
                .factoryId(factoryId)
                .startedAt(startedAt)
                .status(TaskRunStatus.RUNNING)
                .build();
        try {
            runLog = runLogRepository.save(runLog);
        } catch (Exception persistErr) {
            log.warn("[Canvas-Cron] Failed to persist RUNNING run log for taskCode={}: {}",
                    taskCode, persistErr.getMessage());
        }

        TaskRunStatus finalStatus;
        String errorMsg = null;
        try {
            if (!applicationContext.containsBean(handlerBeanName)) {
                throw new IllegalStateException("Handler bean not found: " + handlerBeanName);
            }
            Object beanObj = applicationContext.getBean(handlerBeanName);
            if (!(beanObj instanceof TaskHandler)) {
                throw new IllegalStateException(
                        "Bean '" + handlerBeanName + "' does not implement TaskHandler");
            }
            TaskHandler handler = (TaskHandler) beanObj;

            ScheduledTask freshTask = scheduledTaskRepository.findById(taskId).orElse(null);
            LocalDateTime lastRunAt = freshTask != null ? freshTask.getLastRunAt() : null;

            Map<String, Object> context = new HashMap<>();
            context.put("taskId", taskId);
            context.put("taskCode", taskCode);
            context.put("factoryId", factoryId);
            context.put("lastRunAt", lastRunAt);

            handler.execute(context);
            finalStatus = TaskRunStatus.SUCCESS;
        } catch (Exception e) {
            finalStatus = TaskRunStatus.FAILED;
            errorMsg = truncate(stackTraceToString(e), ERROR_MSG_MAX_LEN);
            log.error("[Canvas-Cron] Task FAILED taskCode={}: {}", taskCode, e.getMessage(), e);
        }

        LocalDateTime finishedAt = LocalDateTime.now();
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;

        try {
            runLog.setFinishedAt(finishedAt);
            runLog.setDurationMs(durationMs);
            runLog.setStatus(finalStatus);
            runLog.setErrorMsg(errorMsg);
            runLogRepository.save(runLog);
        } catch (Exception persistErr) {
            log.warn("[Canvas-Cron] Failed to persist final run log for taskCode={}: {}",
                    taskCode, persistErr.getMessage());
        }

        final TaskRunStatus persistedStatus = finalStatus;
        final String persistedErrorMsg = errorMsg;
        try {
            scheduledTaskRepository.findById(taskId).ifPresent(fresh -> {
                fresh.setLastRunAt(startedAt);
                fresh.setLastRunStatus(persistedStatus);
                fresh.setLastRunError(persistedStatus == TaskRunStatus.FAILED
                        ? truncate(persistedErrorMsg, ERROR_MSG_MAX_LEN)
                        : null);
                scheduledTaskRepository.save(fresh);
            });
        } catch (Exception persistErr) {
            log.warn("[Canvas-Cron] Failed to update scheduled_task last_run_* for taskCode={}: {}",
                    taskCode, persistErr.getMessage());
        }
    }

    /**
     * Reload all tasks from DB. Cancels currently registered futures, then re-registers.
     */
    public synchronized void reload() {
        log.info("[Canvas-Cron] Reloading scheduled tasks from DB...");
        for (ScheduledFuture<?> future : activeFutures) {
            try {
                future.cancel(false);
            } catch (Exception e) {
                log.warn("[Canvas-Cron] Failed to cancel scheduled future: {}", e.getMessage());
            }
        }
        activeFutures.clear();
        registerAllEnabledTasks();
    }

    /**
     * Manually run a task NOW, bypassing the cron schedule.
     * Used by REST {@code /run-now} and AI Tool {@code scheduled_task_run_now}.
     *
     * <p>No ShedLock — manual override is intentional (admin decided to run).
     * Returns the persisted run log row.
     *
     * @param taskId task UUID
     * @return ScheduledTaskRunLog row with final status
     */
    public ScheduledTaskRunLog runNow(UUID taskId) {
        ScheduledTask task = scheduledTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        LocalDateTime startedAt = LocalDateTime.now();
        long startNanos = System.nanoTime();

        ScheduledTaskRunLog runLog = ScheduledTaskRunLog.builder()
                .taskId(task.getId())
                .factoryId(task.getFactoryId())
                .startedAt(startedAt)
                .status(TaskRunStatus.RUNNING)
                .build();
        runLog = runLogRepository.save(runLog);

        TaskRunStatus finalStatus;
        String errorMsg = null;
        try {
            if (!applicationContext.containsBean(task.getHandlerBeanName())) {
                throw new IllegalStateException("Handler bean not found: " + task.getHandlerBeanName());
            }
            Object beanObj = applicationContext.getBean(task.getHandlerBeanName());
            if (!(beanObj instanceof TaskHandler)) {
                throw new IllegalStateException(
                        "Bean '" + task.getHandlerBeanName() + "' does not implement TaskHandler");
            }
            TaskHandler handler = (TaskHandler) beanObj;

            Map<String, Object> context = new HashMap<>();
            context.put("taskId", task.getId());
            context.put("taskCode", task.getTaskCode());
            context.put("factoryId", task.getFactoryId());
            context.put("lastRunAt", task.getLastRunAt());
            context.put("manualRun", true);

            handler.execute(context);
            finalStatus = TaskRunStatus.SUCCESS;
        } catch (Exception e) {
            finalStatus = TaskRunStatus.FAILED;
            errorMsg = truncate(stackTraceToString(e), ERROR_MSG_MAX_LEN);
            log.error("[Canvas-Cron] Manual runNow FAILED taskCode={}: {}",
                    task.getTaskCode(), e.getMessage(), e);
        }

        LocalDateTime finishedAt = LocalDateTime.now();
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;

        runLog.setFinishedAt(finishedAt);
        runLog.setDurationMs(durationMs);
        runLog.setStatus(finalStatus);
        runLog.setErrorMsg(errorMsg);
        runLog = runLogRepository.save(runLog);

        task.setLastRunAt(startedAt);
        task.setLastRunStatus(finalStatus);
        task.setLastRunError(finalStatus == TaskRunStatus.FAILED ? errorMsg : null);
        scheduledTaskRepository.save(task);

        return runLog;
    }

    // ==================== helpers ====================

    /**
     * Render a Throwable's class + message + frames as a single string. No
     * inner length cap — {@link #truncate(String, int)} is the single authoritative
     * truncation point so callers see "...[truncated]" suffix consistently
     * (post-review C2: removed prior 3500-char early-break that produced
     * silently-cut strings with no marker).
     */
    private static String stackTraceToString(Throwable t) {
        if (t == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(t.getClass().getName()).append(": ").append(t.getMessage()).append("\n");
        for (StackTraceElement el : t.getStackTrace()) {
            sb.append("\tat ").append(el.toString()).append("\n");
        }
        return sb.toString();
    }

    /**
     * Truncate a string to {@code max} characters; if truncated, replace the tail
     * with {@value #TRUNCATION_MARKER} (post-review C2: explicit marker so
     * operators know they're seeing a tail-cut, not a complete short message).
     *
     * <p>Caller-visible promise: returned string is {@code <= max} characters.
     * If {@code max < TRUNCATION_MARKER.length()}, output is a hard substring
     * without marker (degenerate case — never triggered with current cap of 4000).
     */
    private static String truncate(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        int markerLen = TRUNCATION_MARKER.length();
        if (max <= markerLen) {
            // Cap is shorter than the marker itself — fall back to hard substring.
            return s.substring(0, max);
        }
        return s.substring(0, max - markerLen) + TRUNCATION_MARKER;
    }
}
