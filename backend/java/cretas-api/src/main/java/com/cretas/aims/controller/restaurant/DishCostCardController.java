package com.cretas.aims.controller.restaurant;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.restaurant.DishCostCardResponse;
import com.cretas.aims.service.restaurant.DishCostCardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 菜品成本卡 Controller (#57 成本卡/出菜反推).
 *
 * <p>替代旧 STUB ({@code RestaurantDishCostAnalysisTool} 仅返菜品/食材计数) 的真成本卡:
 * 逐料食材成本拆解 + 毛利率, 供 web-admin 菜品管理「成本卡」tab 渲染。
 *
 * <p>成本/售价/毛利字段在 {@link DishCostCardResponse} 上标 {@code @PriceSensitive},
 * 由 {@code PriceFieldResponseAdvice} 对无价权角色 (e.g. warehouse_manager) 自动剥离,
 * 无需 controller 显式门控金额。
 *
 * @author Cretas Team
 * @since 2026-06-04 (feature #57)
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/restaurant/dishes")
@RequiredArgsConstructor
@Tag(name = "餐饮-菜品成本卡")
public class DishCostCardController {

    private static final int MAX_PORTIONS = 9999;

    private final DishCostCardService dishCostCardService;

    @GetMapping("/{productTypeId}/cost-card")
    @RequireModule("restaurant")
    @Operation(summary = "菜品成本卡", description = "逐料食材成本拆解 + 毛利率 (按份数缩放)")
    public ApiResponse<DishCostCardResponse> costCard(
            @PathVariable String factoryId,
            @PathVariable String productTypeId,
            @RequestParam(defaultValue = "1") @Parameter(description = "份数 (1-9999)") int portions) {
        int safePortions = Math.max(1, Math.min(MAX_PORTIONS, portions));
        log.info("菜品成本卡: factoryId={}, productTypeId={}, portions={}", factoryId, productTypeId, safePortions);
        DishCostCardResponse card = dishCostCardService.getCostCard(factoryId, productTypeId, safePortions);
        return ApiResponse.success(card);
    }
}
