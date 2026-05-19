package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.cron.ScheduledTask;
import com.cretas.aims.entity.cron.ScheduledTaskRunLog;
import com.cretas.aims.repository.cron.ScheduledTaskRepository;
import com.cretas.aims.repository.cron.ScheduledTaskRunLogRepository;
import com.cretas.aims.service.cron.DynamicSchedulerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

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

    private final DynamicSchedulerService dynamicSchedulerService;
    private final ScheduledTaskRepository taskRepository;
    private final ScheduledTaskRunLogRepository runLogRepository;

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
        ScheduledTask created = dynamicSchedulerService.createTask(task);
        return ApiResponse.success("定时任务已创建", created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "修改定时任务 (partial)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<ScheduledTask> update(@PathVariable UUID id, @RequestBody ScheduledTask patch) {
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
}
