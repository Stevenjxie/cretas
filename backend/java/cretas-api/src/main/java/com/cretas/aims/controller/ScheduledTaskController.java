package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.cron.ScheduledTask;
import com.cretas.aims.entity.cron.ScheduledTaskRunLog;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.cron.ScheduledTaskRepository;
import com.cretas.aims.repository.cron.ScheduledTaskRunLogRepository;
import com.cretas.aims.service.cron.DynamicSchedulerService;
import com.cretas.aims.service.cron.TaskHandler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Canvas-Cron 定时任务管理 (Phase 5 skeleton).
 *
 * <p>Path intentionally <b>does not</b> contain {@code {factoryId}} because tasks
 * can be global (cross-factory, e.g. cache eviction). Factory scope is set via
 * request body / query param.
 *
 * <p>JwtAuthInterceptor whitelist: {@code FACTORY_ID_PATTERN} matches
 * {@code /api/mobile/{xxx}/...} where {@code xxx} is the factory id. Since this
 * controller's URL is {@code /api/mobile/scheduled-tasks/*}, {@code scheduled-tasks}
 * would be captured as the "factory id" segment. Sister chat must add
 * {@code "scheduled-tasks"} to the exclude list in
 * {@code JwtAuthInterceptor.extractFactoryIdFromUrl}, alongside the existing
 * {@code "smartbi-config"}, {@code "system"}, etc.
 *
 * <p>All endpoints require {@code factory_super_admin} or {@code permission_admin}.
 *
 * @since Phase 5 (2026-05-18)
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/scheduled-tasks")
@Tag(name = "Canvas-Cron 定时任务", description = "DB-driven cron tasks 配置 + 执行历史 + 手动触发")
@RequiredArgsConstructor
public class ScheduledTaskController {

    /**
     * Role-set applied to every endpoint via {@code @RequireRole} on each method.
     * {@code RequireRole} is METHOD-only (see annotation @Target), so class-level
     * application is impossible; sister chat keeps roles in sync if expanding.
     */
    private static final String[] ALLOWED_ROLES =
            {"factory_super_admin", "permission_admin"};

    /**
     * AUD-5 B-A3 sister sweep: explicit length caps mirror PG column widths in
     * {@code scheduled_tasks} table (see {@link ScheduledTask} {@code @Column(length=...)}).
     * Without these, an over-length string lets the request reach PG and surfaces as
     * {@code DataIntegrityViolationException} → generic 409 "数据处理异常". Pre-check at
     * controller boundary delivers a specific 400 with a hintTarget instead.
     */
    private static final int TASK_CODE_MAX_LENGTH = 100;
    private static final int TASK_NAME_MAX_LENGTH = 255;
    private static final int CRON_EXPRESSION_MAX_LENGTH = 100;
    private static final int HANDLER_BEAN_NAME_MAX_LENGTH = 255;

    private final DynamicSchedulerService dynamicSchedulerService;
    private final ScheduledTaskRepository taskRepository;
    private final ScheduledTaskRunLogRepository runLogRepository;
    private final ApplicationContext applicationContext;

    @GetMapping
    @Operation(summary = "列出定时任务 (可按 factoryId / enabled 过滤)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<List<ScheduledTask>> list(
            @RequestParam(required = false) String factoryId,
            @RequestParam(required = false) Boolean enabled
    ) {
        List<ScheduledTask> tasks;
        if (factoryId != null && enabled != null && enabled) {
            tasks = taskRepository.findByFactoryIdAndEnabledTrue(factoryId);
        } else if (enabled != null && enabled) {
            tasks = taskRepository.findByEnabledTrue();
        } else {
            tasks = taskRepository.findAll();
            if (factoryId != null) {
                tasks = tasks.stream()
                        .filter(t -> factoryId.equals(t.getFactoryId()))
                        .collect(Collectors.toList());
            }
            if (enabled != null) {
                tasks = tasks.stream()
                        .filter(t -> enabled.equals(t.getEnabled()))
                        .collect(Collectors.toList());
            }
        }
        return ApiResponse.success("查询成功", tasks);
    }

    @PostMapping
    @Operation(summary = "创建定时任务")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<ScheduledTask> create(@RequestBody ScheduledTask task) {
        // Phase 5 sister chat: replace ScheduledTask with a request DTO + @Valid.
        // AUD-5 B-A3 sister sweep: length pre-check produces specific 400 BEFORE
        // dispatching to service layer where PG would surface a generic 409.
        validateNameLengths(task);
        ScheduledTask created = dynamicSchedulerService.createTask(task);
        return ApiResponse.success("定时任务已创建", created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "修改定时任务 (partial)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<ScheduledTask> update(@PathVariable UUID id, @RequestBody ScheduledTask patch) {
        // AUD-5 B-A3 sister sweep (Rule 16: entry-point matrix — create + update
        // are independent code paths). PATCH semantics: only validate fields that
        // are present in the incoming patch (null = "don't touch this field").
        validateNameLengths(patch);
        ScheduledTask updated = dynamicSchedulerService.updateTask(id, patch);
        return ApiResponse.success("定时任务已更新", updated);
    }

    @PostMapping("/{id}/toggle")
    @Operation(summary = "启用/禁用")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<ScheduledTask> toggle(@PathVariable UUID id, @RequestParam boolean enabled) {
        ScheduledTask updated = dynamicSchedulerService.toggleTask(id, enabled);
        return ApiResponse.success(enabled ? "任务已启用" : "任务已禁用", updated);
    }

    @PostMapping("/{id}/run-now")
    @Operation(summary = "立即执行一次 (返回 run log)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<ScheduledTaskRunLog> runNow(@PathVariable UUID id) {
        ScheduledTaskRunLog log = dynamicSchedulerService.runNow(id);
        return ApiResponse.success("手动执行完成", log);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "软删除")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        dynamicSchedulerService.deleteTask(id);
        return ApiResponse.success("定时任务已删除", null);
    }

    @GetMapping("/{id}/logs")
    @Operation(summary = "执行历史 (分页)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Page<ScheduledTaskRunLog>> logs(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Page<ScheduledTaskRunLog> logs = runLogRepository
                .findByTaskIdOrderByStartedAtDesc(id, PageRequest.of(page, size));
        return ApiResponse.success("查询成功", logs);
    }

    @PostMapping("/refresh")
    @Operation(summary = "强制 DynamicScheduler 从 DB 重新加载 (多实例同步 / debug)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Void> refresh() {
        dynamicSchedulerService.reload();
        return ApiResponse.success("DynamicScheduler 已重新加载", null);
    }

    /**
     * List handler bean names registered in the Spring context — used by the
     * Canvas-Cron UI to populate a "handler" dropdown when creating a task,
     * and by AI Tool callers to discover real handler names (post-review I7:
     * previously Tool descriptions hard-coded {@code inventoryReportHandler}
     * which doesn't exist).
     */
    @GetMapping("/handlers")
    @Operation(summary = "列出所有 TaskHandler bean 名称 (用于 UI 选择 + AI Tool 发现)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<List<String>> listHandlers() {
        String[] beanNames = applicationContext.getBeanNamesForType(TaskHandler.class);
        List<String> sorted = Arrays.stream(beanNames)
                .sorted()
                .collect(Collectors.toList());
        return ApiResponse.success("查询成功", sorted);
    }

    // ==================== Boundary validators (AUD-5 B-A3 sister sweep) ====================

    /**
     * AUD-5 B-A3 sister sweep (edge audit 2026-05-20): explicit length pre-check for
     * VARCHAR-bounded fields {@code taskCode} (100), {@code taskName} (255),
     * {@code cronExpression} (100), {@code handlerBeanName} (255).
     *
     * <p>PATCH semantics on update path: only validate fields that the caller actually
     * sent (non-null). Null = "don't touch this field" — the service layer keeps the
     * existing value. This mirrors {@code PricingStrategyController.validateNameLengths}.
     *
     * <p>Without this, an over-length input lets the request reach PG and surfaces as
     * {@link org.springframework.dao.DataIntegrityViolationException} caught by the
     * {@code GlobalExceptionHandler} → generic 409 "数据处理异常" — opaque to users.
     */
    private void validateNameLengths(ScheduledTask task) {
        if (task == null) {
            return;
        }
        String taskCode = task.getTaskCode();
        if (taskCode != null && taskCode.length() > TASK_CODE_MAX_LENGTH) {
            throw new BusinessException(400,
                    "任务代码最长 " + TASK_CODE_MAX_LENGTH + " 字符 (当前 " + taskCode.length() + ")")
                    .withHint("请使用更短的任务代码")
                    .withSeverity("warning")
                    .withHintTarget("taskCode");
        }
        String taskName = task.getTaskName();
        if (taskName != null && taskName.length() > TASK_NAME_MAX_LENGTH) {
            throw new BusinessException(400,
                    "任务名称最长 " + TASK_NAME_MAX_LENGTH + " 字符 (当前 " + taskName.length() + ")")
                    .withHint("请使用更短的任务名称")
                    .withSeverity("warning")
                    .withHintTarget("taskName");
        }
        String cron = task.getCronExpression();
        if (cron != null && cron.length() > CRON_EXPRESSION_MAX_LENGTH) {
            throw new BusinessException(400,
                    "Cron 表达式最长 " + CRON_EXPRESSION_MAX_LENGTH + " 字符 (当前 " + cron.length() + ")")
                    .withHint("请使用更短的 Cron 表达式 (Spring 6 字段: 秒 分 时 日 月 周)")
                    .withSeverity("warning")
                    .withHintTarget("cronExpression");
        }
        String handler = task.getHandlerBeanName();
        if (handler != null && handler.length() > HANDLER_BEAN_NAME_MAX_LENGTH) {
            throw new BusinessException(400,
                    "Handler Bean 名称最长 " + HANDLER_BEAN_NAME_MAX_LENGTH
                            + " 字符 (当前 " + handler.length() + ")")
                    .withHint("请使用更短的 Bean 名称")
                    .withSeverity("warning")
                    .withHintTarget("handlerBeanName");
        }
    }
}
