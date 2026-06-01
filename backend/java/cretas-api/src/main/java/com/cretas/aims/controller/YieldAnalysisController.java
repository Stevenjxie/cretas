package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.yield.ProcessYieldAggDTO;
import com.cretas.aims.service.yield.YieldAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/production/yield")
@Tag(name = "出成率分析", description = "厂级出成率聚合 (只读)")
@RequiredArgsConstructor
@RequireModule("production")
public class YieldAnalysisController {

    private final YieldAnalysisService yieldAnalysisService;

    @GetMapping("/by-process")
    @Operation(summary = "工序级出成率聚合 (厂级跨批, 按报工日期/产品筛选)")
    public ApiResponse<List<ProcessYieldAggDTO>> byProcess(
            @PathVariable String factoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String productTypeId) {
        return ApiResponse.success(
                yieldAnalysisService.aggregateByProcess(factoryId, startDate, endDate, productTypeId));
    }
}
