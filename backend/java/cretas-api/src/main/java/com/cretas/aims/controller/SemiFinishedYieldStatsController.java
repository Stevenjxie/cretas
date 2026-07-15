package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.yield.SemiFinishedYieldStatsDTO;
import com.cretas.aims.service.yield.SemiFinishedYieldStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mobile/{factoryId}/production/yield")
@Tag(name = "半成品出成率", description = "按工厂和半成品 SKU 聚合全部有效历史批次")
@RequiredArgsConstructor
@RequireModule("production")
public class SemiFinishedYieldStatsController {

    private final SemiFinishedYieldStatsService semiFinishedYieldStatsService;

    @GetMapping("/semi-finished/{semiFinishedSkuId}")
    @RequirePermission({"production:read"})
    @Operation(summary = "查询半成品 SKU 全历史加权出成率")
    public ApiResponse<SemiFinishedYieldStatsDTO> getStats(
            @PathVariable String factoryId,
            @PathVariable String semiFinishedSkuId) {
        return ApiResponse.success(
                semiFinishedYieldStatsService.getStats(factoryId, semiFinishedSkuId));
    }
}
