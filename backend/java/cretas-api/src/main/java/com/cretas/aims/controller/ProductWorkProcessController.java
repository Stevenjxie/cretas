package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.ProductWorkProcessDTO;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.service.ProductWorkProcessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/product-work-processes")
@Tag(name = "产品工序关联", description = "产品与工序的关联管理")
@RequiredArgsConstructor
@RequireModule("production_plan")
public class ProductWorkProcessController {

    private final ProductWorkProcessService service;

    @RequirePermission({"production:read_write"})
    @PostMapping
    @Operation(summary = "关联产品与工序")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ApiResponse<ProductWorkProcessDTO> create(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestBody @Valid ProductWorkProcessDTO dto) {
        return ApiResponse.success(service.create(factoryId, dto));
    }

    @GetMapping
    @Operation(summary = "查询产品关联的工序列表")
    public ApiResponse<List<ProductWorkProcessDTO>> listByProduct(
            @PathVariable String factoryId,
            @RequestParam @Parameter(description = "产品类型ID") String productTypeId) {
        return ApiResponse.success(service.listByProduct(factoryId, productTypeId));
    }

    @RequirePermission({"production:read_write"})
    @RequireRole({"factory_super_admin", "workshop_supervisor", "department_admin"})
    @PutMapping("/{id}")
    @Operation(summary = "更新关联(顺序/覆写参数)")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ApiResponse<ProductWorkProcessDTO> update(
            @PathVariable String factoryId,
            @PathVariable Long id,
            @RequestBody @Valid ProductWorkProcessDTO dto) {
        return ApiResponse.success(service.update(factoryId, id, dto));
    }

    @RequirePermission({"production:read_write"})
    @DeleteMapping("/{id}")
    @Operation(summary = "移除关联")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ApiResponse<Void> delete(
            @PathVariable String factoryId,
            @PathVariable Long id) {
        service.delete(factoryId, id);
        return ApiResponse.success();
    }

    @RequirePermission({"production:read_write"})
    @PutMapping("/batch-sort")
    @Operation(summary = "批量调整排序")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ApiResponse<Void> batchSort(
            @PathVariable String factoryId,
            @RequestBody @Valid ProductWorkProcessDTO.BatchSortRequest request) {
        service.batchSort(factoryId, request.getItems());
        return ApiResponse.success();
    }

    @RequirePermission({"production:read_write"})
    @PostMapping("/copy-chain")
    @Operation(summary = "从源产品一键复制整条工序链到目标产品 (目标已有工序链则 409)")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ApiResponse<java.util.Map<String, Object>> copyChain(
            @PathVariable String factoryId,
            @RequestParam @Parameter(description = "源产品ID") String sourceProductId,
            @RequestParam @Parameter(description = "目标产品ID") String targetProductId) {
        int copied = service.copyChain(factoryId, sourceProductId, targetProductId);
        return ApiResponse.success(java.util.Map.of("copiedCount", copied));
    }
}
