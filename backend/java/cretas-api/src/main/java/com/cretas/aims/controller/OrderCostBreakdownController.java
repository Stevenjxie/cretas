package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.service.yield.OrderCostBreakdownService;
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
 * 订单级单盒成本拆分 (混批闭环) — 单一权威端点。
 *
 * <p>沿批次消耗边 T1 谱系遍历 + T2 上游成本回溯, 计算原料/人工/调料/包装/单盒成本及上游各批占比。
 * 价格按 {@code procurement:price:view} 权限脱敏 (PriceMaskResolver, 红线)。
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/production/orders")
@Tag(name = "订单成本拆分", description = "混批闭环单盒成本 (谱系遍历 + 上游成本回溯)")
@RequiredArgsConstructor
@RequireModule("production_report")
public class OrderCostBreakdownController {

    private final OrderCostBreakdownService orderCostBreakdownService;
    private final PriceMaskResolver priceMaskResolver;

    @RequirePermission({"production:read"})
    @GetMapping("/{orderId}/cost-breakdown")
    @Operation(summary = "订单单盒成本拆分 (混批闭环, 原料=上游 traced 成本之和)")
    public ApiResponse<OrderCostBreakdownDTO> getCostBreakdown(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "订单ID") String orderId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        boolean maskPrice = priceMaskResolver.shouldMaskPrice(authorization);
        return ApiResponse.success(orderCostBreakdownService.compute(factoryId, orderId, maskPrice));
    }
}
