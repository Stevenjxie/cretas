package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowResult;
import com.cretas.aims.service.processentry.ProcessSheetService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

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
}
