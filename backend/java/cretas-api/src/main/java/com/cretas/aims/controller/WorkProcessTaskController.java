package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.utils.SecurityUtils;
import com.cretas.aims.service.workprocess.WorkProcessTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 工序任务 Controller (Track D2 — M-WP-1/2).
 *
 * <p>路径对齐 SCHEMA_DESIGN §2.5:
 * <ul>
 *   <li>spawn: POST /api/mobile/{factoryId}/production/batches/{batchId}/spawn-tasks</li>
 *   <li>列表: GET /api/mobile/{factoryId}/work-process-tasks</li>
 *   <li>详情: GET /api/mobile/{factoryId}/work-process-tasks/{id}</li>
 *   <li>start / complete / skip / 修改 / 删除</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "工序任务", description = "工序任务实例的状态机管理")
@RequireModule("production_plan")
public class WorkProcessTaskController {

    private final WorkProcessTaskService service;

    /**
     * 从 product_work_processes 模板 spawn 工序任务.
     */
    @PostMapping("/api/mobile/{factoryId}/production/batches/{batchId}/spawn-tasks")
    @Operation(summary = "为生产批次 spawn 工序任务实例")
    @RequirePermission({"production:read_write"})
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ApiResponse<List<WorkProcessTaskDTO>> spawnTasks(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "生产批次ID") Long batchId,
            @RequestBody @Valid SpawnTasksRequest request) {
        List<String> productTypeIds = request.effectiveProductTypeIds();
        log.info("Spawn 工序任务: factoryId={}, batchId={}, productTypeIds={}",
                factoryId, batchId, productTypeIds);
        // Fable 审计修复 (问题2): 走计划模式感知的 spawn, 与计划转批次主路径一致 — skip=true 的计划补 spawn 时
        // 生成两点哨兵任务 (而非旧的恒逐道), 避免补 spawn 与计划模式矛盾。
        List<WorkProcessTaskDTO> tasks = new ArrayList<>();
        for (String productTypeId : productTypeIds) {
            tasks.addAll(service.spawnTasksForBatch(factoryId, batchId, productTypeId));
        }
        return ApiResponse.success(tasks);
    }

    /**
     * 列表 (分页 + 过滤).
     */
    @GetMapping("/api/mobile/{factoryId}/work-process-tasks")
    @Operation(summary = "工序任务列表")
    public ApiResponse<PageResponse<WorkProcessTaskDTO>> list(
            @PathVariable String factoryId,
            @RequestParam(required = false) WorkProcessTask.Status status,
            @RequestParam(required = false) Long batchId,
            @RequestParam(required = false) Long assignedTo,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        Pageable pageable = PageRequest.of(
                Math.max(0, page - 1), size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ApiResponse.success(
                service.list(factoryId, status, batchId, assignedTo, pageable));
    }

    /**
     * 列出某批次的工序任务 (不分页, 按 processOrder 升序).
     *
     * <p>供 RN 报工屏 (YieldStepReportScreen) 和 ProductionBatch 详情页"工序任务"tab 使用.
     * <ul>
     *   <li>小组长传 {@code ?assignedTo={userId}} → 仅返回本人被分配的任务 + 未指派的任务</li>
     *   <li>主管不传 {@code assignedTo} → 返回全部任务</li>
     * </ul>
     * 详见 WorkProcessTaskService#listByBatch(String, Long, Long) 过滤规则.
     */
    @GetMapping("/api/mobile/{factoryId}/production/batches/{batchId}/work-process-tasks")
    @Operation(summary = "查询批次的工序任务 (按 processOrder 升序, 支持按小组长过滤)")
    public ApiResponse<List<WorkProcessTaskDTO>> listByBatch(
            @PathVariable String factoryId,
            @PathVariable Long batchId,
            @RequestParam(required = false) Long assignedTo) {
        return ApiResponse.success(service.listByBatch(factoryId, batchId, assignedTo));
    }

    /**
     * 详情.
     */
    @GetMapping("/api/mobile/{factoryId}/work-process-tasks/{id}")
    @Operation(summary = "工序任务详情")
    public ApiResponse<WorkProcessTaskDTO> getById(
            @PathVariable String factoryId,
            @PathVariable Long id) {
        return ApiResponse.success(service.getById(factoryId, id));
    }

    /**
     * 开始工序: PENDING → IN_PROGRESS.
     */
    @PutMapping("/api/mobile/{factoryId}/work-process-tasks/{id}/start")
    @Operation(summary = "开始工序 (PENDING → IN_PROGRESS)")
    @RequirePermission({"production:read_write"})
    public ApiResponse<WorkProcessTaskDTO> start(
            @PathVariable String factoryId,
            @PathVariable Long id) {
        Long operatorId = SecurityUtils.getCurrentUserId();
        return ApiResponse.success(service.start(factoryId, id, operatorId));
    }

    /**
     * 完成工序: IN_PROGRESS → COMPLETED.
     */
    @PutMapping("/api/mobile/{factoryId}/work-process-tasks/{id}/complete")
    @Operation(summary = "完成工序 (IN_PROGRESS → COMPLETED, 必填 actualQuantity)")
    @RequirePermission({"production:read_write"})
    public ApiResponse<WorkProcessTaskDTO> complete(
            @PathVariable String factoryId,
            @PathVariable Long id,
            @RequestBody @Valid WorkProcessTaskDTO.CompleteRequest request) {
        Long operatorId = SecurityUtils.getCurrentUserId();
        return ApiResponse.success(service.complete(factoryId, id, operatorId, request));
    }

    /**
     * 跳过工序: 任意非终态 → SKIPPED, 必填 notes, 限主管.
     */
    @PutMapping("/api/mobile/{factoryId}/work-process-tasks/{id}/skip")
    @Operation(summary = "跳过工序 (任意非终态 → SKIPPED, 必填 notes)")
    @RequirePermission({"production:read_write"})
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ApiResponse<WorkProcessTaskDTO> skip(
            @PathVariable String factoryId,
            @PathVariable Long id,
            @RequestBody @Valid WorkProcessTaskDTO.SkipRequest request) {
        Long operatorId = SecurityUtils.getCurrentUserId();
        return ApiResponse.success(service.skip(factoryId, id, operatorId, request));
    }

    /**
     * 修改计划 (分配责任人 / 计划时间 / 备注).
     */
    @PutMapping("/api/mobile/{factoryId}/work-process-tasks/{id}")
    @Operation(summary = "修改工序任务计划 (分配责任人 / 计划时间 / 备注)")
    @RequirePermission({"production:read_write"})
    public ApiResponse<WorkProcessTaskDTO> updatePlan(
            @PathVariable String factoryId,
            @PathVariable Long id,
            @RequestBody @Valid WorkProcessTaskDTO.UpdatePlanRequest request) {
        return ApiResponse.success(service.updatePlan(factoryId, id, request));
    }

    /**
     * 软删工序任务.
     */
    @DeleteMapping("/api/mobile/{factoryId}/work-process-tasks/{id}")
    @Operation(summary = "软删工序任务")
    @RequirePermission({"production:read_write"})
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ApiResponse<Void> delete(
            @PathVariable String factoryId,
            @PathVariable Long id) {
        service.delete(factoryId, id);
        return ApiResponse.success();
    }

    // ==================== Request DTO ====================

    @lombok.Data
    public static class SpawnTasksRequest {
        private String productTypeId;
        private List<String> productTypeIds;

        @AssertTrue(message = "productTypeId/productTypeIds 不能同时为空")
        public boolean hasProductType() {
            return !effectiveProductTypeIds().isEmpty();
        }

        public List<String> effectiveProductTypeIds() {
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            if (productTypeId != null && !productTypeId.isBlank()) {
                ids.add(productTypeId);
            }
            if (productTypeIds != null) {
                for (String id : productTypeIds) {
                    if (id != null && !id.isBlank()) {
                        ids.add(id);
                    }
                }
            }
            return new ArrayList<>(ids);
        }
    }
}
