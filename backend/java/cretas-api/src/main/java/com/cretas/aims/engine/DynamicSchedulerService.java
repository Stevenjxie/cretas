package com.cretas.aims.engine;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.entity.config.FactorySchedulerConfig;
import com.cretas.aims.repository.config.FactorySchedulerConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class DynamicSchedulerService implements com.cretas.aims.service.config.SchedulerReloadPort {

    private final FactorySchedulerConfigRepository schedulerRepo;
    private final ToolRegistry toolRegistry;

    // Self-reference for proxy-aware @Transactional dispatch (R7 G2 persistExecutionMetadata
    // needs REQUIRES_NEW, which Spring AOP only honors through the proxy). @Lazy breaks
    // the self-circular dependency at wire time. Mirrors TriggerChainExecutor pattern.
    @Autowired @Lazy
    private DynamicSchedulerService self;
    private final ObjectMapper objectMapper;

    private final Map<String, ScheduledFuture<?>> activeTasks = new ConcurrentHashMap<>();
    private volatile TaskScheduler taskScheduler;

    private TaskScheduler getOrCreateScheduler() {
        if (taskScheduler == null) {
            synchronized (this) {
                if (taskScheduler == null) {
                    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
                    scheduler.setPoolSize(2);
                    scheduler.setThreadNamePrefix("canvas-scheduler-");
                    scheduler.initialize();
                    taskScheduler = scheduler;
                }
            }
        }
        return taskScheduler;
    }

    @PostConstruct
    public void loadSchedules() {
        List<FactorySchedulerConfig> configs = schedulerRepo.findByEnabledTrue();
        log.info("Loading {} dynamic scheduled tasks", configs.size());
        for (FactorySchedulerConfig config : configs) {
            scheduleTask(config);
        }
    }

    @PreDestroy
    public void shutdown() {
        activeTasks.values().forEach(f -> f.cancel(false));
        activeTasks.clear();
    }

    public void scheduleTask(FactorySchedulerConfig config) {
        String key = config.getFactoryId() + ":" + config.getTaskCode();
        ScheduledFuture<?> existing = activeTasks.get(key);
        if (existing != null) { existing.cancel(false); activeTasks.remove(key); }

        try {
            ScheduledFuture<?> future = getOrCreateScheduler().schedule(
                    () -> executeTask(config), new CronTrigger(config.getCronExpression()));
            activeTasks.put(key, future);
            log.info("Scheduled task: {} [{}] cron={}", key, config.getToolOrMethod(), config.getCronExpression());
        } catch (IllegalArgumentException e) {
            log.error("Invalid cron for {}: {} — {}", key, config.getCronExpression(), e.getMessage());
        }
    }

    public void reloadSchedule(String factoryId, String taskCode) {
        Optional<FactorySchedulerConfig> config = schedulerRepo.findByFactoryIdAndTaskCode(factoryId, taskCode);
        if (config.isPresent() && config.get().getEnabled()) {
            scheduleTask(config.get());
        } else {
            cancelTask(factoryId, taskCode);
        }
    }

    public void reloadAll() {
        activeTasks.values().forEach(f -> f.cancel(false));
        activeTasks.clear();
        loadSchedules();
    }

    public void cancelTask(String factoryId, String taskCode) {
        String key = factoryId + ":" + taskCode;
        ScheduledFuture<?> future = activeTasks.remove(key);
        if (future != null) { future.cancel(false); log.info("Cancelled: {}", key); }
    }

    public int getActiveTaskCount() { return activeTasks.size(); }

    private void executeTask(FactorySchedulerConfig config) {
        String toolOrMethod = config.getToolOrMethod();
        if (toolOrMethod == null || toolOrMethod.isBlank()) return;

        // R7 G2: track execution outcome for observability (E2E J2-7d + ops diagnostics).
        // Final state gets persisted via self.persistExecutionMetadata (REQUIRES_NEW tx)
        // so the write commits even if the tool throws.
        final String[] statusHolder = { "SUCCESS" };
        final String[] errorHolder = { null };
        final java.time.LocalDateTime startedAt = java.time.LocalDateTime.now();

        Optional<ToolExecutor> executor = toolRegistry.getExecutor(toolOrMethod);
        if (executor.isEmpty()) {
            log.warn("Scheduled tool not found: {}", toolOrMethod);
            statusHolder[0] = "TOOL_NOT_FOUND";
            errorHolder[0] = "tool not registered: " + toolOrMethod;
        } else {
            try {
                String argsJson = objectMapper.writeValueAsString(config.getParams());
                ToolCall toolCall = ToolCall.of("sched-" + config.getTaskCode(), toolOrMethod, argsJson);
                executor.get().execute(toolCall, Map.of("factoryId", config.getFactoryId()));
            } catch (Exception e) {
                log.error("Scheduled task {} failed: {}", config.getTaskCode(), e.getMessage(), e);
                statusHolder[0] = "FAILED";
                errorHolder[0] = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }

        // Persist through self proxy so @Transactional(REQUIRES_NEW) takes effect.
        self.persistExecutionMetadata(
            config.getFactoryId(), config.getTaskCode(),
            startedAt, statusHolder[0], errorHolder[0]);
    }

    /**
     * R7 G2: best-effort write of execution metadata. Refetch the config row so we
     * don't race against concurrent config updates (Quartz holds the original object
     * from scheduleTask time; the row may have been re-saved in between).
     * Failure to persist metadata must not crash the scheduler thread.
     *
     * REQUIRES_NEW: scheduler threads have no ambient transaction, but being explicit
     * keeps parity with TriggerChainExecutor where event-dispatch threads may carry
     * the publisher's transaction. Consistent pattern across both executors.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistExecutionMetadata(String factoryId,
                                         String taskCode,
                                         java.time.LocalDateTime startedAt,
                                         String status,
                                         String errorMessage) {
        try {
            schedulerRepo.findByFactoryIdAndTaskCode(factoryId, taskCode)
                .ifPresent(fresh -> {
                    fresh.setLastExecutedAt(startedAt);
                    fresh.setLastExecutionStatus(status);
                    fresh.setLastExecutionError(errorMessage);
                    fresh.setExecutionCount((fresh.getExecutionCount() == null ? 0L : fresh.getExecutionCount()) + 1);
                    schedulerRepo.save(fresh);
                });
        } catch (Exception persistErr) {
            log.warn("Failed to persist scheduler execution metadata for {}: {}",
                    taskCode, persistErr.getMessage());
        }
    }
}
