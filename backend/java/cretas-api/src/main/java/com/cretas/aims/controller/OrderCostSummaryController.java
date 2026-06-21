package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.yield.OrderCostSummaryRowDTO;
import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.service.yield.OrderCostSummaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 成本汇总页 (对标客户 M67 Excel「汇总页」按订单/按天多行视图)。
 * 价格按 procurement:price:view 脱敏。
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/production")
@Tag(name = "成本汇总", description = "订单成本汇总多行表 (混批闭环单盒成本 × 区间)")
@RequiredArgsConstructor
@RequireModule("production_report")
public class OrderCostSummaryController {

    private final OrderCostSummaryService orderCostSummaryService;
    private final PriceMaskResolver priceMaskResolver;

    @RequirePermission({"production:read"})
    @GetMapping("/cost-summary")
    @Operation(summary = "订单成本汇总 (下单日期区间内每张订单一行)")
    public ApiResponse<List<OrderCostSummaryRowDTO>> getCostSummary(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Parameter(description = "起始下单日期") LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Parameter(description = "截止下单日期") LocalDate endDate,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        boolean maskPrice = priceMaskResolver.shouldMaskPrice(authorization);
        return ApiResponse.success(orderCostSummaryService.summarize(factoryId, startDate, endDate, maskPrice));
    }
}
