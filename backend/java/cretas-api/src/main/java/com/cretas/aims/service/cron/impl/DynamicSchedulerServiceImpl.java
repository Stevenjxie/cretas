package com.cretas.aims.service.cron.impl;

import com.cretas.aims.entity.cron.ScheduledTask;
import com.cretas.aims.entity.cron.ScheduledTaskRunLog;
import com.cretas.aims.entity.cron.TaskRunStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.cron.ScheduledTaskRepository;
import com.cretas.aims.repository.cron.ScheduledTaskRunLogRepository;
import com.cretas.aims.service.cron.DynamicScheduler;
import com.cretas.aims.service.cron.DynamicSchedulerService;
import com.cretas.aims.service.cron.TaskHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementation of {@link DynamicSchedulerService} for Canvas-Cron Phase 5.
 *
 * <p>每个 mutation 方法在持久化完成后调用 {@link DynamicScheduler#reload()}, 让
 * 调度器在数秒内 cancel-and-rebuild 当前任务集 — 用户在 UI 改 cron 立即生效, 无需 JVM 重启.
 *
 * <p>校验流程:
 * <ul>
 *   <li>cron syntax — {@link CronExpression#parse(String)} 解析失败抛 BusinessException(400)</li>
 *   <li>handler bean exists — {@link ApplicationContext#containsBean(String)} +
 *       检查类型实现 {@link TaskHandler}</li>
 *   <li>uniqueness (task_code) — 依赖 DB partial unique index, 重复抛 DataIntegrityViolation
 *       由全局 ExceptionHandler 转 409</li>
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
    private final ApplicationContext applicationContext;

    /**
     * Manual {@code runNow} rate-limit window. If a RUNNING log row for the same
     * task started within this window, a new manual run is rejected with HTTP 409
     * (post-review I3+I8 fix — prevents blue/green double-execute + super-admin DOS).
     */
    private static final long RUN_NOW_RATE_LIMIT_SECONDS = 60L;

    @Override
    public void reload() {
        dynamicScheduler.reload();
    }

    @Override
    public ScheduledTaskRunLog runNow(UUID taskId) {
        if (taskId == null) {
            throw new BusinessException(400, "任务 ID 不能为空")
                    .withHint("请提供有效的 task UUID");
        }
        if (taskRepository.findById(taskId).isEmpty()) {
            throw new BusinessException(404, "定时任务不存在: " + taskId)
                    .withHint("请确认 taskId 正确, 或先创建任务");
        }
        // Rate-limit guard (post-review I3+I8): reject if a recent execution is
        // still RUNNING within the rate-limit window. Prevents blue/green double-
        // execute and limits manual-run DOS surface for super-admins.
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(RUN_NOW_RATE_LIMIT_SECONDS);
        long runningRecent = runLogRepository.countByTaskIdAndStatusAndStartedAtAfter(
                taskId, TaskRunStatus.RUNNING, threshold);
        if (runningRecent > 0) {
            log.info("[Canvas-Cron] runNow rate-limited taskId={} recent RUNNING count={}",
                    taskId, runningRecent);
            throw new BusinessException(409,
                    "上次执行尚未完成 (" + RUN_NOW_RATE_LIMIT_SECONDS + " 秒内), 请稍后再试")
                    .withHint("请查看执行历史等待当前 RUNNING 任务完成后再触发");
        }
        return dynamicScheduler.runNow(taskId);
    }

    @Override
    @Transactional
    public ScheduledTask createTask(ScheduledTask task) {
        if (task == null) {
            throw new BusinessException(400, "请求体不能为空");
        }
        validateNotBlank(task.getTaskCode(), "taskCode");
        validateNotBlank(task.getCronExpression(), "cronExpression");
        validateNotBlank(task.getHandlerBeanName(), "handlerBeanName");
        validateCronExpression(task.getCronExpression());
        validateHandlerBean(task.getHandlerBeanName());

        // Uniqueness guard (post-review C1) — scope-aware lookup. Matches the partial
        // unique indexes idx_scheduled_tasks_global_code / idx_scheduled_tasks_factory_code.
        // Distinct scopes (global vs per-factory, or different factoryIds) may reuse the same
        // taskCode legitimately, so we query only the relevant scope here.
        Optional<ScheduledTask> existing = findExistingByScope(task.getFactoryId(), task.getTaskCode());
        if (existing.isPresent()) {
            ScheduledTask e = existing.get();
            String scopeText = e.getFactoryId() == null ? "global" : ("factory=" + e.getFactoryId());
            throw new BusinessException(409,
                    "任务代码已存在: " + task.getTaskCode() + " (scope=" + scopeText
                            + ", id=" + e.getId() + ")")
                    .withHint("请改名或先删除旧任务");
        }

        if (task.getEnabled() == null) {
            task.setEnabled(true);
        }
        ScheduledTask saved = taskRepository.save(task);
        log.info("[Canvas-Cron] Created task: taskCode={} cron={} handler={} factoryId={} enabled={}",
                saved.getTaskCode(), saved.getCronExpression(),
                saved.getHandlerBeanName(), saved.getFactoryId(), saved.getEnabled());

        dynamicScheduler.reload();
        return saved;
    }

    @Override
    @Transactional
    public ScheduledTask updateTask(UUID taskId, ScheduledTask patch) {
        if (taskId == null) {
            throw new BusinessException(400, "taskId 不能为空");
        }
        ScheduledTask existing = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(404, "定时任务不存在: " + taskId)
                        .withHint("请检查 taskId 或先创建任务"));

        boolean schedulerAffectingChange = false;
        if (patch != null) {
            if (patch.getTaskName() != null) {
                existing.setTaskName(patch.getTaskName());
            }
            if (patch.getCronExpression() != null && !patch.getCronExpression().isBlank()) {
                validateCronExpression(patch.getCronExpression());
                if (!patch.getCronExpression().equals(existing.getCronExpression())) {
                    existing.setCronExpression(patch.getCronExpression());
                    schedulerAffectingChange = true;
                }
            }
            if (patch.getHandlerBeanName() != null && !patch.getHandlerBeanName().isBlank()) {
                validateHandlerBean(patch.getHandlerBeanName());
                if (!patch.getHandlerBeanName().equals(existing.getHandlerBeanName())) {
                    existing.setHandlerBeanName(patch.getHandlerBeanName());
                    schedulerAffectingChange = true;
                }
            }
            if (patch.getEnabled() != null && !patch.getEnabled().equals(existing.getEnabled())) {
                existing.setEnabled(patch.getEnabled());
                schedulerAffectingChange = true;
            }
            if (patch.getFactoryId() != null && !patch.getFactoryId().equals(existing.getFactoryId())) {
                // Re-check uniqueness in the destination scope (post-review C1).
                // Moving a task to a new scope must not collide with an existing taskCode
                // in that scope.
                Optional<ScheduledTask> collide = findExistingByScope(
                        patch.getFactoryId(), existing.getTaskCode());
                if (collide.isPresent() && !collide.get().getId().equals(existing.getId())) {
                    ScheduledTask c = collide.get();
                    String scopeText = c.getFactoryId() == null
                            ? "global" : ("factory=" + c.getFactoryId());
                    throw new BusinessException(409,
                            "目标 scope 已存在同名任务: " + existing.getTaskCode()
                                    + " (scope=" + scopeText + ", id=" + c.getId() + ")")
                            .withHint("请先在目标 scope 中改名/删除冲突任务再迁移");
                }
                existing.setFactoryId(patch.getFactoryId());
                // factoryId is rebuilt into Runnable context — treat as scheduler-affecting
                schedulerAffectingChange = true;
            }
        }
        ScheduledTask saved = taskRepository.save(existing);
        log.info("[Canvas-Cron] Updated task: taskCode={} schedulerAffectingChange={}",
                saved.getTaskCode(), schedulerAffectingChange);
        if (schedulerAffectingChange) {
            dynamicScheduler.reload();
        }
        return saved;
    }

    @Override
    @Transactional
    public ScheduledTask toggleTask(UUID taskId, boolean enabled) {
        if (taskId == null) {
            throw new BusinessException(400, "taskId 不能为空");
        }
        ScheduledTask existing = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(404, "定时任务不存在: " + taskId));
        if (existing.getEnabled() != null && existing.getEnabled() == enabled) {
            log.info("[Canvas-Cron] toggleTask no-op: taskCode={} already enabled={}",
                    existing.getTaskCode(), enabled);
            return existing;
        }
        existing.setEnabled(enabled);
        ScheduledTask saved = taskRepository.save(existing);
        log.info("[Canvas-Cron] Toggled task: taskCode={} enabled={}", saved.getTaskCode(), enabled);
        dynamicScheduler.reload();
        return saved;
    }

    @Override
    @Transactional
    public void deleteTask(UUID taskId) {
        if (taskId == null) {
            throw new BusinessException(400, "taskId 不能为空");
        }
        ScheduledTask existing = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(404, "定时任务不存在: " + taskId));
        // @SQLDelete on BaseEntity sets deleted_at = NOW() instead of physical delete
        taskRepository.delete(existing);
        log.info("[Canvas-Cron] Soft-deleted task: taskCode={}", existing.getTaskCode());
        dynamicScheduler.reload();
    }

    // ==================== validation helpers ====================

    /**
     * Scope-aware uniqueness lookup (post-review C1). Mirrors the partial unique
     * indexes — global tasks (factoryId NULL) checked via
     * {@link ScheduledTaskRepository#findByTaskCodeAndFactoryIdIsNull(String)},
     * per-factory tasks via
     * {@link ScheduledTaskRepository#findByFactoryIdAndTaskCode(String, String)}.
     *
     * <p>Empty/blank factoryId is treated as null (global), matching how the
     * legacy controller surfaces an empty body parameter.
     */
    private Optional<ScheduledTask> findExistingByScope(String factoryId, String taskCode) {
        if (factoryId == null || factoryId.isBlank()) {
            return taskRepository.findByTaskCodeAndFactoryIdIsNull(taskCode);
        }
        return taskRepository.findByFactoryIdAndTaskCode(factoryId, taskCode);
    }

    private void validateNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(400, fieldName + " 不能为空")
                    .withHint("请补全 " + fieldName + " 字段");
        }
    }

    private void validateCronExpression(String cron) {
        try {
            CronExpression.parse(cron);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400,
                    "无效的 cron 表达式: " + cron + " (" + e.getMessage() + ")")
                    .withHint("Spring cron 用 6 字段格式: 秒 分 时 日 月 周, 如 '0 0 9 1 * ?'");
        }
    }

    private void validateHandlerBean(String beanName) {
        if (!applicationContext.containsBean(beanName)) {
            throw new BusinessException(400,
                    "Handler bean 不存在: " + beanName)
                    .withHint("请检查 bean 名拼写, 或确认对应 @Component 已注册");
        }
        Object bean = applicationContext.getBean(beanName);
        if (!(bean instanceof TaskHandler)) {
            throw new BusinessException(400,
                    "Bean '" + beanName + "' 未实现 TaskHandler 接口")
                    .withHint("handler bean 必须 implements com.cretas.aims.service.cron.TaskHandler");
        }
    }
}
