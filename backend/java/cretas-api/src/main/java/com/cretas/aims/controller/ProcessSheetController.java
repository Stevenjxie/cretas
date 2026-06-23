package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.processentry.ProcessSheetInventoryItem;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowResult;
import com.cretas.aims.dto.processentry.ProcessSheetRowView;
import com.cretas.aims.service.processentry.ProcessSheetService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * SP-F 逐工序电子表格端点 (spec §4)。
 * 每行 upsert 一次 (saveRow)，支持软删 (deleteRow)。
 * 读端点 (inventory、rows 列表) 见后续任务。
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/production-plans/{planId}/process-sheet")
@RequiredArgsConstructor
public class ProcessSheetController {

    private final ProcessSheetService service;

    @RequirePermission({"production:read_write"})
    @PostMapping("/row")
    public ApiResponse<ProcessSheetRowResult> saveRow(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String planId,
            @RequestAttribute("userId") Long userId,
            @Valid @RequestBody ProcessSheetRowRequest req) {
        return ApiResponse.success("保存成功", service.saveRow(factoryId, planId, req, userId));
    }

    @RequirePermission({"production:read_write"})
    @DeleteMapping("/row/{clientRowId}")
    public ApiResponse<Void> deleteRow(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String planId,
            @PathVariable @NotBlank String clientRowId) {
        service.deleteRow(factoryId, planId, clientRowId);
        return ApiResponse.success("删除成功", null);
    }

    /**
     * SP-F Task 2.1: 读取指定工序的 WIP 在制品库存视图。
     *
     * <p>只读端点 — 权限 "production:read"，与 OrderCostBreakdownController / OrderYieldController
     * 等生产读端点一致 (比写端点 "production:read_write" 宽松一级，让只读角色也可访问)。
     *
     * <p>URL 示例: GET /api/mobile/{factoryId}/production-plans/{planId}/process-sheet/inventory?process=xiuyou
     */
    @RequirePermission({"production:read"})
    @GetMapping("/inventory")
    public ApiResponse<List<ProcessSheetInventoryItem>> getInventory(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String planId,
            @RequestParam @NotBlank String process) {
        return ApiResponse.success(service.getInventory(factoryId, planId, process));
    }

    /**
     * SP-F Task 2.2: 读回指定工序下已保存的行列表 (供前端电子表格重载)。
     *
     * <p>URL 示例: GET /api/mobile/{factoryId}/production-plans/{planId}/process-sheet/rows?process=xiuyou
     */
    @RequirePermission({"production:read"})
    @GetMapping("/rows")
    public ApiResponse<List<ProcessSheetRowView>> getRows(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String planId,
            @RequestParam @NotBlank String process) {
        return ApiResponse.success(service.getRows(factoryId, planId, process));
    }
}
