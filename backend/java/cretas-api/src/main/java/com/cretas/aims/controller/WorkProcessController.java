package com.cretas.aims.controller;

import com.cretas.aims.dto.WorkProcessDTO;
import com.cretas.aims.dto.UpdateWorkProcessOutputKindRequest;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.service.WorkProcessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/work-processes")
@Tag(name = "工序管理", description = "工序主数据管理接口")
@RequiredArgsConstructor
public class WorkProcessController {

    private final WorkProcessService workProcessService;

    @RequirePermission({"production:read_write"})
    @PostMapping
    @Operation(summary = "创建工序")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ApiResponse<WorkProcessDTO> create(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @RequestBody @Valid WorkProcessDTO dto) {
        log.info("Creating work process for factory: {}", factoryId);
        return ApiResponse.success(workProcessService.create(factoryId, dto));
    }

    @GetMapping
    @Operation(summary = "工序列表(分页)")
    public ApiResponse<PageResponse<WorkProcessDTO>> list(
            @PathVariable String factoryId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size,
            @RequestParam(defaultValue = "sortOrder") String sortBy,
            @RequestParam(defaultValue = "ASC") String sortDirection) {
        Sort.Direction direction = "ASC".equalsIgnoreCase(sortDirection) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(Math.max(0, page - 1), size, Sort.by(direction, sortBy));
        return ApiResponse.success(workProcessService.list(factoryId, pageable));
    }

    @GetMapping("/active")
    @Operation(summary = "获取所有启用的工序")
    public ApiResponse<List<WorkProcessDTO>> listActive(
            @PathVariable String factoryId) {
        return ApiResponse.success(workProcessService.listActive(factoryId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "工序详情")
    public ApiResponse<WorkProcessDTO> getById(
            @PathVariable String factoryId,
            @PathVariable String id) {
        return ApiResponse.success(workProcessService.getById(factoryId, id));
    }

    @RequirePermission({"production:read_write"})
    @PutMapping("/{id}")
    @Operation(summary = "更新工序")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ApiResponse<WorkProcessDTO> update(
            @PathVariable String factoryId,
            @PathVariable String id,
            @RequestBody @Valid WorkProcessDTO dto) {
        log.info("Updating work process {} for factory: {}", id, factoryId);
        return ApiResponse.success(workProcessService.update(factoryId, id, dto));
    }

    @RequirePermission({"production:read_write"})
    @PatchMapping("/{id}/output-kind")
    @Operation(summary = "快捷修改工序主产出类型")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ApiResponse<WorkProcessDTO> updateOutputMaterialKind(
            @PathVariable String factoryId,
            @PathVariable String id,
            @RequestBody @Valid UpdateWorkProcessOutputKindRequest request) {
        log.info("Updating work process {} output kind for factory: {}", id, factoryId);
        return ApiResponse.success(workProcessService.updateOutputMaterialKind(
                factoryId, id, request.getDefaultOutputMaterialKind()));
    }

    @RequirePermission({"production:read_write"})
    @DeleteMapping("/{id}")
    @Operation(summary = "删除工序")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(
            @PathVariable String factoryId,
            @PathVariable String id) {
        log.info("Deleting work process {} for factory: {}", id, factoryId);
        workProcessService.delete(factoryId, id);
        return ApiResponse.success();
    }

    @RequirePermission({"production:read_write"})
    @PutMapping("/{id}/toggle-status")
    @Operation(summary = "切换工序启用/禁用状态")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ApiResponse<WorkProcessDTO> toggleStatus(
            @PathVariable String factoryId,
            @PathVariable String id) {
        return ApiResponse.success(workProcessService.toggleStatus(factoryId, id));
    }

    @RequirePermission({"production:read_write"})
    @PutMapping("/sort-order")
    @Operation(summary = "批量更新排序")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ApiResponse<Void> updateSortOrder(
            @PathVariable String factoryId,
            @RequestBody @Valid List<WorkProcessDTO.SortOrderUpdate> updates) {
        workProcessService.updateSortOrder(factoryId, updates);
        return ApiResponse.success();
    }

    /**
     * C5: Detect duplicate work-process clusters.
     * Returns groups of work-processes that share the same (processName, processCategory, unit).
     * Only groups with ≥ 2 members are returned.
     */
    @GetMapping("/duplicates")
    @Operation(summary = "检测重复工序（同名称+类别+单位）")
    public ApiResponse<List<WorkProcessDTO.DuplicateGroup>> detectDuplicates(
            @PathVariable String factoryId) {
        return ApiResponse.success(workProcessService.detectDuplicates(factoryId));
    }
}
