package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.MaterialInputRequest;
import com.cretas.aims.dto.yield.YieldReportRequest;
import com.cretas.aims.service.yield.YieldReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/production/batches/{batchId}")
@Tag(name = "出成率报工", description = "Phase A — 逐道工序投入/产出双量报工 + 出成率派生")
@RequiredArgsConstructor
@RequireModule("production_report")
public class YieldReportController {

    private final YieldReportService yieldReportService;

    @RequirePermission({"production:read_write"})
    @PostMapping("/reports")
    @Operation(summary = "逐道报工 (投入+产出双量)")
    public ApiResponse<Map<String, Object>> submitReport(
            @PathVariable String factoryId,
            @PathVariable Long batchId,
            @RequestBody YieldReportRequest req,
            @RequestAttribute("userId") Long workerId) {
        return ApiResponse.success(yieldReportService.submitReport(factoryId, batchId, workerId, req));
    }

    @RequirePermission({"production:read_write"})
    @PutMapping("/material-input")
    @Operation(summary = "领料环节记 出库量+投料量 (首道)")
    public ApiResponse<Map<String, Object>> recordMaterialInput(
            @PathVariable String factoryId,
            @PathVariable Long batchId,
            @RequestBody MaterialInputRequest req,
            @RequestAttribute("userId") Long workerId) {
        return ApiResponse.success(yieldReportService.recordMaterialInput(factoryId, batchId, workerId, req));
    }

    @RequirePermission({"production:read"})
    @GetMapping("/yield")
    @Operation(summary = "整批+单工序出成率 (派生)")
    public ApiResponse<BatchYieldDTO> getYield(
            @PathVariable String factoryId,
            @PathVariable Long batchId) {
        return ApiResponse.success(yieldReportService.getYield(factoryId, batchId));
    }

    @RequirePermission({"production:read_write"})
    @PostMapping("/settle-day")
    @Operation(summary = "人工标记每日结清 (triggerComplete=true 末道结清回写批次)")
    public ApiResponse<Map<String, Object>> settleDay(
            @PathVariable String factoryId,
            @PathVariable Long batchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false, defaultValue = "false") boolean triggerComplete,
            @RequestAttribute("userId") Long workerId) {
        return ApiResponse.success(yieldReportService.settleDay(factoryId, batchId, workerId, date, triggerComplete));
    }

    @RequirePermission({"production:read"})
    @GetMapping("/reports")
    @Operation(summary = "报工流水汇总 (按工序聚合视图)")
    public ApiResponse<BatchYieldDTO> listReports(
            @PathVariable String factoryId,
            @PathVariable Long batchId) {
        // 复用 getYield 的 steps 即流水汇总视图; 明细 append-only 流水 Phase D RN 接入时按需扩展
        return ApiResponse.success(yieldReportService.getYield(factoryId, batchId));
    }
}
