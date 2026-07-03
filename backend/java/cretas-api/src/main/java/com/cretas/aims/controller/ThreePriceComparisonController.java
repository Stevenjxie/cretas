package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.yield.ThreePriceSkuDTO;
import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.service.yield.ThreePriceComparisonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 六扇门 D1 延伸: per-SKU「三价对比」看板 (标准BOM成本 / 销售价 / 实际成本)。
 *
 * <p>不重复造超支报警口径 — 直接复用 {@code OrderCostAlarmListener} 同款
 * {@code StandardCostService} + {@code CostVarianceService}。价格按
 * {@code procurement:price:view} 权限脱敏 (与 {@code OrderCostSummaryController} /
 * {@code OrderCostBreakdownController} 同一显式 PriceMaskResolver 手法)。
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/cost")
@Tag(name = "三价对比看板", description = "per-SKU 标准BOM成本 vs 销售价 vs 实际成本, 超支报警口径同源")
@RequiredArgsConstructor
@RequireModule("production_report")
public class ThreePriceComparisonController {

    private final ThreePriceComparisonService threePriceComparisonService;
    private final PriceMaskResolver priceMaskResolver;

    @RequirePermission({"production:read"})
    @GetMapping("/three-price-comparison")
    @Operation(summary = "per-SKU 三价对比 (标准BOM成本/销售价/实际成本 + 超支标记), 超支 SKU 优先排序")
    public ApiResponse<List<ThreePriceSkuDTO>> getThreePriceComparison(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(required = false, defaultValue = "false")
            @Parameter(description = "true=只返回当前超支的 SKU") boolean overBudgetOnly,
            @RequestParam(required = false)
            @Parameter(description = "按产品分类过滤 (可选)") String category,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        boolean maskPrice = priceMaskResolver.shouldMaskPrice(authorization);
        return ApiResponse.success(
                threePriceComparisonService.compareBySku(factoryId, maskPrice, overBudgetOnly, category));
    }
}
