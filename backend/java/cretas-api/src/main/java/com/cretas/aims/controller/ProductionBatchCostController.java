package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.service.yield.OrderCostBreakdownService;
import com.cretas.aims.service.yield.YieldReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SP-C: 按批次号查出成率 + 成本拆分 (存货生产无订单号场景).
 *
 * <p>路径含 {@code {factoryId}} 段 — JwtAuthInterceptor 工厂守卫自动校验 token.factoryId == path.factoryId。
 * service 层均调用 {@code findByFactoryIdAndBatchNumber} (factory-scoped) — 跨租户安全。
 *
 * <p>脱敏策略与 by-order 保持一致: maskPrice = procurement:price:view 门控
 * (OrderCostBreakdownDTO 是强类型 DTO, 已防止裸 Map NPE 等泄漏)。
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/production/batches")
@Tag(name = "批次出成率与成本", description = "SP-C — 按批次号查出成率 / 单盒成本 (存货生产无订单号场景)")
@RequiredArgsConstructor
@RequireModule("production_report")
public class ProductionBatchCostController {

    private final YieldReportService yieldReportService;
    private final OrderCostBreakdownService orderCostBreakdownService;
    private final PriceMaskResolver priceMaskResolver;

    /**
     * 按批次号查出成率.
     *
     * <p>多租户安全: factory guard (path {factoryId}) + service findByFactoryIdAndBatchNumber。
     */
    @RequirePermission({"production:read"})
    @GetMapping("/{batchNumber}/yield-summary")
    @Operation(summary = "按批次号查出成率 (存货生产无订单号)")
    public ApiResponse<BatchYieldDTO> getBatchYield(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "批次号, 如 PB-20260622-XXXXX") String batchNumber) {
        return ApiResponse.success(yieldReportService.getBatchYieldByNumber(factoryId, batchNumber));
    }

    /**
     * 按批次号查单盒成本拆分.
     *
     * <p>多租户安全: factory guard (path {factoryId}) + service findByFactoryIdAndBatchNumber。
     * 价格按 procurement:price:view 权限脱敏 (与 by-order endpoint 一致)。
     */
    @RequirePermission({"production:read"})
    @GetMapping("/{batchNumber}/cost-breakdown")
    @Operation(summary = "按批次号查单盒成本拆分 (存货生产无订单号)")
    public ApiResponse<OrderCostBreakdownDTO> getBatchCostBreakdown(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "批次号, 如 PB-20260622-XXXXX") String batchNumber,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        boolean maskPrice = priceMaskResolver.shouldMaskPrice(authorization);
        return ApiResponse.success(orderCostBreakdownService.computeByBatch(factoryId, batchNumber, maskPrice));
    }
}
